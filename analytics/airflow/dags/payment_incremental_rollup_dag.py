"""
payment_incremental_rollup_dag.py
==================================
Runs every 15 minutes and incrementally upserts new transaction data from
the source `payment` PostgreSQL DB into `payment_analytics` summary tables.

Two independent task groups run in parallel:
  - customer_pipeline  → customer_txn_hourly_summary
  - org_pipeline       → org_txn_hourly_summary

Watermarks in `pipeline_watermarks` track the last-processed txn_date so
each run only touches rows newer than the previous run's high-water mark.

Airflow Connections required (set via UI, env-var, or airflow-init container):
  - payment_db           : source PostgreSQL (DB: payment)
  - payment_analytics_db : analytics PostgreSQL (DB: payment_analytics)
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta

from airflow.decorators import dag, task, task_group
from airflow.providers.postgres.hooks.postgres import PostgresHook

log = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
SOURCE_CONN_ID    = "payment_db"
ANALYTICS_CONN_ID = "payment_analytics_db"

DEFAULT_ARGS = {
    "owner": "analytics",
    "retries": 3,
    "retry_delay": timedelta(minutes=2),
    "retry_exponential_backoff": False,
    "email_on_failure": False,
    "email_on_retry": False,
}


def _on_failure_callback(context: dict) -> None:
    """Log structured failure details for alerting / observability."""
    dag_id  = context["dag"].dag_id
    task_id = context["task_instance"].task_id
    run_id  = context["run_id"]
    exc     = context.get("exception")
    log.error(
        "DAG failure | dag=%s | task=%s | run_id=%s | error=%s",
        dag_id, task_id, run_id, exc,
    )


# ---------------------------------------------------------------------------
# DAG definition
# ---------------------------------------------------------------------------
@dag(
    dag_id="payment_incremental_rollup",
    description="Incremental 15-min rollup of payment transactions into hourly summaries",
    schedule="*/15 * * * *",
    start_date=datetime(2024, 1, 1),
    catchup=False,
    max_active_runs=1,
    default_args=DEFAULT_ARGS,
    on_failure_callback=_on_failure_callback,
    tags=["payment", "incremental"],
    doc_md="""
    ## Payment Incremental Rollup
    Reads rows from `customer_wallet_transactions` and `org_wallet_transactions`
    that are newer than the stored watermark, aggregates them into hourly
    summary tables, then advances the watermark.
    """,
)
def payment_incremental_rollup():

    # -----------------------------------------------------------------------
    # Task 1 — read both watermarks in a single query
    # -----------------------------------------------------------------------
    @task(task_id="get_watermarks")
    def get_watermarks() -> dict:
        hook = PostgresHook(postgres_conn_id=ANALYTICS_CONN_ID)
        with hook.get_conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT pipeline_name, last_processed_at
                    FROM   payment_analytics.pipeline_watermarks
                    WHERE  pipeline_name IN (
                               'customer_hourly_rollup',
                               'org_hourly_rollup'
                           )
                    """,
                )
                rows = cur.fetchall()

        watermarks = {row[0]: row[1].isoformat() for row in rows}
        log.info("Loaded watermarks: %s", watermarks)

        # Provide sensible defaults if rows are missing
        watermarks.setdefault("customer_hourly_rollup", "2020-01-01T00:00:00")
        watermarks.setdefault("org_hourly_rollup",      "2020-01-01T00:00:00")
        return watermarks

    # -----------------------------------------------------------------------
    # TaskGroup — customer pipeline
    # -----------------------------------------------------------------------
    @task_group(group_id="customer_pipeline")
    def customer_pipeline(watermarks: dict):

        @task(task_id="extract_customer_count")
        def extract_customer_count(watermarks: dict) -> int:
            """COUNT new customer rows since the last watermark."""
            watermark_ts = watermarks["customer_hourly_rollup"]
            hook = PostgresHook(postgres_conn_id=SOURCE_CONN_ID)
            with hook.get_conn() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        SELECT COUNT(*)
                        FROM   customer_wallet_transactions
                        WHERE  txn_date > %s
                        """,
                        (watermark_ts,),
                    )
                    count = cur.fetchone()[0]
            log.info("Customer new rows since %s: %d", watermark_ts, count)
            return count

        @task(task_id="rollup_customer_hourly")
        def rollup_customer_hourly(watermarks: dict) -> str:
            """
            UPSERT hourly aggregates for all new customer transactions.
            Returns the new high-water mark timestamp as an ISO string.
            """
            watermark_ts = watermarks["customer_hourly_rollup"]

            upsert_sql = """
                INSERT INTO payment_analytics.customer_txn_hourly_summary (
                    customer_id,
                    txn_hour,
                    counterparty_id,
                    counterparty_name,
                    counterparty_type,
                    transfer_type,
                    status,
                    txn_count,
                    total_amount,
                    total_fees,
                    total_net_amount,
                    min_amount,
                    max_amount,
                    avg_amount,
                    refreshed_at
                )
                SELECT
                    customer_id,
                    DATE_TRUNC('hour', txn_date)             AS txn_hour,
                    COALESCE(counterparty_id, '')             AS counterparty_id,
                    MAX(counterparty_name)                    AS counterparty_name,
                    MAX(counterparty_type)                    AS counterparty_type,
                    COALESCE(transfer_type, '')               AS transfer_type,
                    COALESCE(status, '')                      AS status,
                    COUNT(*)                                  AS txn_count,
                    SUM(amount)                               AS total_amount,
                    SUM(fees)                                 AS total_fees,
                    SUM(net_amount)                           AS total_net_amount,
                    MIN(amount)                               AS min_amount,
                    MAX(amount)                               AS max_amount,
                    AVG(amount)                               AS avg_amount,
                    NOW()                                     AS refreshed_at
                FROM customer_wallet_transactions
                WHERE txn_date > %(watermark)s
                GROUP BY
                    customer_id,
                    DATE_TRUNC('hour', txn_date),
                    COALESCE(counterparty_id, ''),
                    COALESCE(transfer_type, ''),
                    COALESCE(status, '')
                ON CONFLICT (customer_id, txn_hour, counterparty_id, transfer_type, status)
                DO UPDATE SET
                    counterparty_name   = EXCLUDED.counterparty_name,
                    counterparty_type   = EXCLUDED.counterparty_type,
                    txn_count           = EXCLUDED.txn_count,
                    total_amount        = EXCLUDED.total_amount,
                    total_fees          = EXCLUDED.total_fees,
                    total_net_amount    = EXCLUDED.total_net_amount,
                    min_amount          = EXCLUDED.min_amount,
                    max_amount          = EXCLUDED.max_amount,
                    avg_amount          = EXCLUDED.avg_amount,
                    refreshed_at        = EXCLUDED.refreshed_at
                RETURNING txn_hour
            """

            new_watermark_sql = """
                SELECT MAX(txn_date)
                FROM   customer_wallet_transactions
                WHERE  txn_date > %(watermark)s
            """

            src_hook  = PostgresHook(postgres_conn_id=SOURCE_CONN_ID)
            dest_hook = PostgresHook(postgres_conn_id=ANALYTICS_CONN_ID)

            # Determine the new watermark from the source DB
            with src_hook.get_conn() as src_conn:
                with src_conn.cursor() as cur:
                    cur.execute(new_watermark_sql, {"watermark": watermark_ts})
                    new_wm = cur.fetchone()[0]

            if new_wm is None:
                log.info("No new customer transactions since %s. Skipping upsert.", watermark_ts)
                return watermark_ts

            # Execute the upsert in the analytics DB using source DB-linked data
            # Because source and analytics are separate DBs we fetch into Python.
            fetch_sql = """
                SELECT
                    customer_id,
                    DATE_TRUNC('hour', txn_date)             AS txn_hour,
                    COALESCE(counterparty_id, '')             AS counterparty_id,
                    MAX(counterparty_name)                    AS counterparty_name,
                    MAX(counterparty_type)                    AS counterparty_type,
                    COALESCE(transfer_type, '')               AS transfer_type,
                    COALESCE(status, '')                      AS status,
                    COUNT(*)                                  AS txn_count,
                    SUM(amount)                               AS total_amount,
                    SUM(fees)                                 AS total_fees,
                    SUM(net_amount)                           AS total_net_amount,
                    MIN(amount)                               AS min_amount,
                    MAX(amount)                               AS max_amount,
                    AVG(amount)                               AS avg_amount
                FROM customer_wallet_transactions
                WHERE txn_date > %(watermark)s
                GROUP BY
                    customer_id,
                    DATE_TRUNC('hour', txn_date),
                    COALESCE(counterparty_id, ''),
                    COALESCE(transfer_type, ''),
                    COALESCE(status, '')
            """

            with src_hook.get_conn() as src_conn:
                with src_conn.cursor() as cur:
                    cur.execute(fetch_sql, {"watermark": watermark_ts})
                    rows = cur.fetchall()

            if not rows:
                log.info("Aggregation query returned 0 rows. Watermark unchanged.")
                return watermark_ts

            dest_insert_sql = """
                INSERT INTO payment_analytics.customer_txn_hourly_summary (
                    customer_id, txn_hour, counterparty_id, counterparty_name,
                    counterparty_type, transfer_type, status,
                    txn_count, total_amount, total_fees, total_net_amount,
                    min_amount, max_amount, avg_amount, refreshed_at
                )
                VALUES (
                    %s, %s, %s, %s, %s, %s, %s,
                    %s, %s, %s, %s, %s, %s, %s, NOW()
                )
                ON CONFLICT (customer_id, txn_hour, counterparty_id, transfer_type, status)
                DO UPDATE SET
                    counterparty_name   = EXCLUDED.counterparty_name,
                    counterparty_type   = EXCLUDED.counterparty_type,
                    txn_count           = EXCLUDED.txn_count,
                    total_amount        = EXCLUDED.total_amount,
                    total_fees          = EXCLUDED.total_fees,
                    total_net_amount    = EXCLUDED.total_net_amount,
                    min_amount          = EXCLUDED.min_amount,
                    max_amount          = EXCLUDED.max_amount,
                    avg_amount          = EXCLUDED.avg_amount,
                    refreshed_at        = EXCLUDED.refreshed_at
            """

            with dest_hook.get_conn() as dest_conn:
                with dest_conn.cursor() as cur:
                    cur.executemany(dest_insert_sql, rows)
                dest_conn.commit()

            log.info("Upserted %d customer hourly summary rows. New watermark: %s", len(rows), new_wm)
            return new_wm.isoformat()

        @task(task_id="update_customer_watermark")
        def update_customer_watermark(new_watermark: str, row_count: int) -> None:
            """Advance the customer_hourly_rollup watermark in analytics DB."""
            hook = PostgresHook(postgres_conn_id=ANALYTICS_CONN_ID)
            with hook.get_conn() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        UPDATE payment_analytics.pipeline_watermarks
                        SET    last_processed_at = %s,
                               last_run_at       = NOW(),
                               records_processed = records_processed + %s
                        WHERE  pipeline_name = 'customer_hourly_rollup'
                        """,
                        (new_watermark, row_count),
                    )
                conn.commit()
            log.info(
                "Updated customer_hourly_rollup watermark to %s (+%d records)",
                new_watermark, row_count,
            )

        # Wire up the task group
        count      = extract_customer_count(watermarks)
        new_wm     = rollup_customer_hourly(watermarks)
        update_customer_watermark(new_wm, count)

    # -----------------------------------------------------------------------
    # TaskGroup — org pipeline (runs in parallel with customer_pipeline)
    # -----------------------------------------------------------------------
    @task_group(group_id="org_pipeline")
    def org_pipeline(watermarks: dict):

        @task(task_id="extract_org_count")
        def extract_org_count(watermarks: dict) -> int:
            """COUNT new org rows since the last watermark."""
            watermark_ts = watermarks["org_hourly_rollup"]
            hook = PostgresHook(postgres_conn_id=SOURCE_CONN_ID)
            with hook.get_conn() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        SELECT COUNT(*)
                        FROM   org_wallet_transactions
                        WHERE  txn_date > %s
                        """,
                        (watermark_ts,),
                    )
                    count = cur.fetchone()[0]
            log.info("Org new rows since %s: %d", watermark_ts, count)
            return count

        @task(task_id="rollup_org_hourly")
        def rollup_org_hourly(watermarks: dict) -> str:
            """
            UPSERT hourly aggregates for all new org transactions.
            Returns the new high-water mark timestamp as an ISO string.
            """
            watermark_ts = watermarks["org_hourly_rollup"]

            new_watermark_sql = """
                SELECT MAX(txn_date)
                FROM   org_wallet_transactions
                WHERE  txn_date > %(watermark)s
            """

            fetch_sql = """
                SELECT
                    org_id,
                    COALESCE(division_id, '')                 AS division_id,
                    DATE_TRUNC('hour', txn_date)              AS txn_hour,
                    COALESCE(counterparty_id, '')              AS counterparty_id,
                    MAX(counterparty_name)                     AS counterparty_name,
                    MAX(counterparty_type)                     AS counterparty_type,
                    COALESCE(transfer_type, '')                AS transfer_type,
                    COALESCE(status, '')                       AS status,
                    COUNT(*)                                   AS txn_count,
                    SUM(amount)                                AS total_amount,
                    SUM(fees)                                  AS total_fees,
                    SUM(net_amount)                            AS total_net_amount,
                    MIN(amount)                                AS min_amount,
                    MAX(amount)                                AS max_amount,
                    AVG(amount)                                AS avg_amount
                FROM org_wallet_transactions
                WHERE txn_date > %(watermark)s
                GROUP BY
                    org_id,
                    COALESCE(division_id, ''),
                    DATE_TRUNC('hour', txn_date),
                    COALESCE(counterparty_id, ''),
                    COALESCE(transfer_type, ''),
                    COALESCE(status, '')
            """

            src_hook  = PostgresHook(postgres_conn_id=SOURCE_CONN_ID)
            dest_hook = PostgresHook(postgres_conn_id=ANALYTICS_CONN_ID)

            with src_hook.get_conn() as src_conn:
                with src_conn.cursor() as cur:
                    cur.execute(new_watermark_sql, {"watermark": watermark_ts})
                    new_wm = cur.fetchone()[0]

            if new_wm is None:
                log.info("No new org transactions since %s. Skipping upsert.", watermark_ts)
                return watermark_ts

            with src_hook.get_conn() as src_conn:
                with src_conn.cursor() as cur:
                    cur.execute(fetch_sql, {"watermark": watermark_ts})
                    rows = cur.fetchall()

            if not rows:
                log.info("Org aggregation returned 0 rows. Watermark unchanged.")
                return watermark_ts

            dest_insert_sql = """
                INSERT INTO payment_analytics.org_txn_hourly_summary (
                    org_id, division_id, txn_hour, counterparty_id, counterparty_name,
                    counterparty_type, transfer_type, status,
                    txn_count, total_amount, total_fees, total_net_amount,
                    min_amount, max_amount, avg_amount, refreshed_at
                )
                VALUES (
                    %s, %s, %s, %s, %s, %s, %s, %s,
                    %s, %s, %s, %s, %s, %s, %s, NOW()
                )
                ON CONFLICT (org_id, division_id, txn_hour, counterparty_id, transfer_type, status)
                DO UPDATE SET
                    counterparty_name   = EXCLUDED.counterparty_name,
                    counterparty_type   = EXCLUDED.counterparty_type,
                    txn_count           = EXCLUDED.txn_count,
                    total_amount        = EXCLUDED.total_amount,
                    total_fees          = EXCLUDED.total_fees,
                    total_net_amount    = EXCLUDED.total_net_amount,
                    min_amount          = EXCLUDED.min_amount,
                    max_amount          = EXCLUDED.max_amount,
                    avg_amount          = EXCLUDED.avg_amount,
                    refreshed_at        = EXCLUDED.refreshed_at
            """

            with dest_hook.get_conn() as dest_conn:
                with dest_conn.cursor() as cur:
                    cur.executemany(dest_insert_sql, rows)
                dest_conn.commit()

            log.info("Upserted %d org hourly summary rows. New watermark: %s", len(rows), new_wm)
            return new_wm.isoformat()

        @task(task_id="update_org_watermark")
        def update_org_watermark(new_watermark: str, row_count: int) -> None:
            """Advance the org_hourly_rollup watermark in analytics DB."""
            hook = PostgresHook(postgres_conn_id=ANALYTICS_CONN_ID)
            with hook.get_conn() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        UPDATE payment_analytics.pipeline_watermarks
                        SET    last_processed_at = %s,
                               last_run_at       = NOW(),
                               records_processed = records_processed + %s
                        WHERE  pipeline_name = 'org_hourly_rollup'
                        """,
                        (new_watermark, row_count),
                    )
                conn.commit()
            log.info(
                "Updated org_hourly_rollup watermark to %s (+%d records)",
                new_watermark, row_count,
            )

        # Wire up the task group
        count  = extract_org_count(watermarks)
        new_wm = rollup_org_hourly(watermarks)
        update_org_watermark(new_wm, count)

    # -----------------------------------------------------------------------
    # DAG wire-up — customer and org pipelines run in parallel
    # -----------------------------------------------------------------------
    wm = get_watermarks()
    customer_pipeline(wm)
    org_pipeline(wm)


# Instantiate the DAG
payment_incremental_rollup()
