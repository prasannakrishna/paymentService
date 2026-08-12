"""
payment_daily_rollup_dag.py
============================
Daily rollup DAG (schedule: 1 AM IST).

Tasks:
  1. compute_daily_customer_summary  — full UPSERT for yesterday into customer_txn_daily_summary
  2. compute_daily_org_summary       — full UPSERT for yesterday into org_txn_daily_summary
  3. compute_monthly_customer_summary— UPSERT month-to-date into customer_txn_monthly_summary
  4. compute_monthly_org_summary     — UPSERT month-to-date into org_txn_monthly_summary
  5. check_if_weekly                 — BranchPythonOperator: routes to weekly task on Mondays
  6. compute_weekly_summary          — runs only on Monday; logs a weekly digest
  7. skip_weekly_summary             — empty end node for non-Monday branches
  8. data_freshness_check            — verifies analytics data is not stale (gap > 2h → WARNING)

Airflow Connections required:
  - payment_db           : source PostgreSQL (DB: payment)
  - payment_analytics_db : analytics PostgreSQL (DB: payment_analytics)
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, date

from airflow.decorators import dag, task
from airflow.operators.empty import EmptyOperator
from airflow.operators.python import BranchPythonOperator
from airflow.providers.postgres.hooks.postgres import PostgresHook

log = logging.getLogger(__name__)

SOURCE_CONN_ID    = "payment_db"
ANALYTICS_CONN_ID = "payment_analytics_db"

DEFAULT_ARGS = {
    "owner": "analytics",
    "retries": 2,
    "retry_delay": timedelta(minutes=5),
    "email_on_failure": False,
    "email_on_retry": False,
}


def _on_failure_callback(context: dict) -> None:
    dag_id  = context["dag"].dag_id
    task_id = context["task_instance"].task_id
    run_id  = context["run_id"]
    exc     = context.get("exception")
    log.error(
        "DAG failure | dag=%s | task=%s | run_id=%s | error=%s",
        dag_id, task_id, run_id, exc,
    )


@dag(
    dag_id="payment_daily_rollup",
    description="Daily summary rollup for customer and org transactions",
    schedule="0 1 * * *",       # 1 AM every day
    start_date=datetime(2024, 1, 1),
    catchup=False,
    max_active_runs=1,
    default_args=DEFAULT_ARGS,
    on_failure_callback=_on_failure_callback,
    tags=["payment", "daily"],
    doc_md="""
    ## Payment Daily Rollup
    Refreshes daily and monthly summary tables for both customer and org
    transaction pipelines. On Mondays, also emits a weekly digest log.
    A freshness check at the end warns if analytics data has gone stale.
    """,
)
def payment_daily_rollup():

    # -----------------------------------------------------------------------
    # Task 1 — compute_daily_customer_summary
    # -----------------------------------------------------------------------
    @task(task_id="compute_daily_customer_summary")
    def compute_daily_customer_summary(**context) -> int:
        """
        Full UPSERT of yesterday's data into customer_txn_daily_summary.
        Runs in the analytics DB using data fetched from the source DB.
        """
        logical_date: datetime = context["logical_date"]
        yesterday: date = (logical_date - timedelta(days=1)).date()
        today: date     = logical_date.date()

        log.info("Computing daily customer summary for %s", yesterday)

        fetch_sql = """
            SELECT
                customer_id,
                DATE(txn_date)                           AS txn_date,
                COALESCE(counterparty_id, '')            AS counterparty_id,
                MAX(counterparty_name)                   AS counterparty_name,
                MAX(counterparty_type)                   AS counterparty_type,
                COALESCE(transfer_type, '')              AS transfer_type,
                COALESCE(status, '')                     AS status,
                COUNT(*)                                 AS txn_count,
                SUM(amount)                              AS total_amount,
                SUM(fees)                                AS total_fees,
                SUM(net_amount)                          AS total_net_amount,
                MIN(amount)                              AS min_amount,
                MAX(amount)                              AS max_amount,
                AVG(amount)                              AS avg_amount
            FROM customer_wallet_transactions
            WHERE txn_date >= %(day_start)s
              AND txn_date  < %(day_end)s
            GROUP BY
                customer_id,
                DATE(txn_date),
                COALESCE(counterparty_id, ''),
                COALESCE(transfer_type, ''),
                COALESCE(status, '')
        """

        upsert_sql = """
            INSERT INTO payment_analytics.customer_txn_daily_summary (
                customer_id, txn_date, counterparty_id, counterparty_name,
                counterparty_type, transfer_type, status,
                txn_count, total_amount, total_fees, total_net_amount,
                min_amount, max_amount, avg_amount, refreshed_at
            )
            VALUES (
                %s, %s, %s, %s, %s, %s, %s,
                %s, %s, %s, %s, %s, %s, %s, NOW()
            )
            ON CONFLICT (customer_id, txn_date, counterparty_id, transfer_type, status)
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

        src_hook  = PostgresHook(postgres_conn_id=SOURCE_CONN_ID)
        dest_hook = PostgresHook(postgres_conn_id=ANALYTICS_CONN_ID)

        with src_hook.get_conn() as src_conn:
            with src_conn.cursor() as cur:
                cur.execute(fetch_sql, {"day_start": yesterday, "day_end": today})
                rows = cur.fetchall()

        if not rows:
            log.info("No customer transactions found for %s.", yesterday)
            return 0

        with dest_hook.get_conn() as dest_conn:
            with dest_conn.cursor() as cur:
                cur.executemany(upsert_sql, rows)
            dest_conn.commit()

        # Update watermark
        with dest_hook.get_conn() as dest_conn:
            with dest_conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE payment_analytics.pipeline_watermarks
                    SET    last_processed_at = %s,
                           last_run_at       = NOW(),
                           records_processed = records_processed + %s
                    WHERE  pipeline_name = 'customer_daily_rollup'
                    """,
                    (str(today), len(rows)),
                )
            dest_conn.commit()

        log.info("Upserted %d customer daily summary rows for %s.", len(rows), yesterday)
        return len(rows)

    # -----------------------------------------------------------------------
    # Task 2 — compute_daily_org_summary
    # -----------------------------------------------------------------------
    @task(task_id="compute_daily_org_summary")
    def compute_daily_org_summary(**context) -> int:
        """Full UPSERT of yesterday's data into org_txn_daily_summary."""
        logical_date: datetime = context["logical_date"]
        yesterday: date = (logical_date - timedelta(days=1)).date()
        today: date     = logical_date.date()

        log.info("Computing daily org summary for %s", yesterday)

        fetch_sql = """
            SELECT
                org_id,
                COALESCE(division_id, '')                AS division_id,
                DATE(txn_date)                           AS txn_date,
                COALESCE(counterparty_id, '')            AS counterparty_id,
                MAX(counterparty_name)                   AS counterparty_name,
                MAX(counterparty_type)                   AS counterparty_type,
                COALESCE(transfer_type, '')              AS transfer_type,
                COALESCE(status, '')                     AS status,
                COUNT(*)                                 AS txn_count,
                SUM(amount)                              AS total_amount,
                SUM(fees)                                AS total_fees,
                SUM(net_amount)                          AS total_net_amount,
                MIN(amount)                              AS min_amount,
                MAX(amount)                              AS max_amount,
                AVG(amount)                              AS avg_amount
            FROM org_wallet_transactions
            WHERE txn_date >= %(day_start)s
              AND txn_date  < %(day_end)s
            GROUP BY
                org_id,
                COALESCE(division_id, ''),
                DATE(txn_date),
                COALESCE(counterparty_id, ''),
                COALESCE(transfer_type, ''),
                COALESCE(status, '')
        """

        upsert_sql = """
            INSERT INTO payment_analytics.org_txn_daily_summary (
                org_id, division_id, txn_date, counterparty_id, counterparty_name,
                counterparty_type, transfer_type, status,
                txn_count, total_amount, total_fees, total_net_amount,
                min_amount, max_amount, avg_amount, refreshed_at
            )
            VALUES (
                %s, %s, %s, %s, %s, %s, %s, %s,
                %s, %s, %s, %s, %s, %s, %s, NOW()
            )
            ON CONFLICT (org_id, division_id, txn_date, counterparty_id, transfer_type, status)
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

        src_hook  = PostgresHook(postgres_conn_id=SOURCE_CONN_ID)
        dest_hook = PostgresHook(postgres_conn_id=ANALYTICS_CONN_ID)

        with src_hook.get_conn() as src_conn:
            with src_conn.cursor() as cur:
                cur.execute(fetch_sql, {"day_start": yesterday, "day_end": today})
                rows = cur.fetchall()

        if not rows:
            log.info("No org transactions found for %s.", yesterday)
            return 0

        with dest_hook.get_conn() as dest_conn:
            with dest_conn.cursor() as cur:
                cur.executemany(upsert_sql, rows)
            dest_conn.commit()

        with dest_hook.get_conn() as dest_conn:
            with dest_conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE payment_analytics.pipeline_watermarks
                    SET    last_processed_at = %s,
                           last_run_at       = NOW(),
                           records_processed = records_processed + %s
                    WHERE  pipeline_name = 'org_daily_rollup'
                    """,
                    (str(today), len(rows)),
                )
            dest_conn.commit()

        log.info("Upserted %d org daily summary rows for %s.", len(rows), yesterday)
        return len(rows)

    # -----------------------------------------------------------------------
    # Task 3 — compute_monthly_customer_summary
    # -----------------------------------------------------------------------
    @task(task_id="compute_monthly_customer_summary")
    def compute_monthly_customer_summary(**context) -> int:
        """
        UPSERT customer month-to-date aggregates into customer_txn_monthly_summary.
        Recomputes the entire current month so partial-month data stays accurate.
        """
        logical_date: datetime = context["logical_date"]
        month_start = logical_date.replace(day=1).date()
        # Re-aggregate up to (but not including) today
        month_end   = logical_date.date()

        log.info("Computing monthly customer summary for %s – %s", month_start, month_end)

        fetch_sql = """
            SELECT
                customer_id,
                EXTRACT(YEAR  FROM txn_date)::SMALLINT   AS txn_year,
                EXTRACT(MONTH FROM txn_date)::SMALLINT   AS txn_month,
                COALESCE(counterparty_id, '')            AS counterparty_id,
                MAX(counterparty_name)                   AS counterparty_name,
                MAX(counterparty_type)                   AS counterparty_type,
                COALESCE(transfer_type, '')              AS transfer_type,
                COALESCE(status, '')                     AS status,
                COUNT(*)                                 AS txn_count,
                SUM(amount)                              AS total_amount,
                SUM(fees)                                AS total_fees,
                SUM(net_amount)                          AS total_net_amount,
                MIN(amount)                              AS min_amount,
                MAX(amount)                              AS max_amount,
                AVG(amount)                              AS avg_amount
            FROM customer_wallet_transactions
            WHERE txn_date >= %(month_start)s
              AND txn_date  < %(month_end)s
            GROUP BY
                customer_id,
                EXTRACT(YEAR  FROM txn_date),
                EXTRACT(MONTH FROM txn_date),
                COALESCE(counterparty_id, ''),
                COALESCE(transfer_type, ''),
                COALESCE(status, '')
        """

        upsert_sql = """
            INSERT INTO payment_analytics.customer_txn_monthly_summary (
                customer_id, txn_year, txn_month,
                counterparty_id, counterparty_name, counterparty_type,
                transfer_type, status,
                txn_count, total_amount, total_fees, total_net_amount,
                min_amount, max_amount, avg_amount, refreshed_at
            )
            VALUES (
                %s, %s, %s, %s, %s, %s, %s, %s,
                %s, %s, %s, %s, %s, %s, %s, NOW()
            )
            ON CONFLICT (customer_id, txn_year, txn_month, counterparty_id, transfer_type, status)
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

        src_hook  = PostgresHook(postgres_conn_id=SOURCE_CONN_ID)
        dest_hook = PostgresHook(postgres_conn_id=ANALYTICS_CONN_ID)

        with src_hook.get_conn() as src_conn:
            with src_conn.cursor() as cur:
                cur.execute(fetch_sql, {"month_start": month_start, "month_end": month_end})
                rows = cur.fetchall()

        if not rows:
            log.info("No customer transactions found for %s/%s.", month_start.year, month_start.month)
            return 0

        with dest_hook.get_conn() as dest_conn:
            with dest_conn.cursor() as cur:
                cur.executemany(upsert_sql, rows)
            dest_conn.commit()

        with dest_hook.get_conn() as dest_conn:
            with dest_conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE payment_analytics.pipeline_watermarks
                    SET    last_run_at       = NOW(),
                           records_processed = records_processed + %s
                    WHERE  pipeline_name = 'customer_monthly_rollup'
                    """,
                    (len(rows),),
                )
            dest_conn.commit()

        log.info("Upserted %d customer monthly summary rows.", len(rows))
        return len(rows)

    # -----------------------------------------------------------------------
    # Task 4 — compute_monthly_org_summary
    # -----------------------------------------------------------------------
    @task(task_id="compute_monthly_org_summary")
    def compute_monthly_org_summary(**context) -> int:
        """UPSERT org month-to-date aggregates into org_txn_monthly_summary."""
        logical_date: datetime = context["logical_date"]
        month_start = logical_date.replace(day=1).date()
        month_end   = logical_date.date()

        log.info("Computing monthly org summary for %s – %s", month_start, month_end)

        fetch_sql = """
            SELECT
                org_id,
                COALESCE(division_id, '')                AS division_id,
                EXTRACT(YEAR  FROM txn_date)::SMALLINT   AS txn_year,
                EXTRACT(MONTH FROM txn_date)::SMALLINT   AS txn_month,
                COALESCE(counterparty_id, '')            AS counterparty_id,
                MAX(counterparty_name)                   AS counterparty_name,
                MAX(counterparty_type)                   AS counterparty_type,
                COALESCE(transfer_type, '')              AS transfer_type,
                COALESCE(status, '')                     AS status,
                COUNT(*)                                 AS txn_count,
                SUM(amount)                              AS total_amount,
                SUM(fees)                                AS total_fees,
                SUM(net_amount)                          AS total_net_amount,
                MIN(amount)                              AS min_amount,
                MAX(amount)                              AS max_amount,
                AVG(amount)                              AS avg_amount
            FROM org_wallet_transactions
            WHERE txn_date >= %(month_start)s
              AND txn_date  < %(month_end)s
            GROUP BY
                org_id,
                COALESCE(division_id, ''),
                EXTRACT(YEAR  FROM txn_date),
                EXTRACT(MONTH FROM txn_date),
                COALESCE(counterparty_id, ''),
                COALESCE(transfer_type, ''),
                COALESCE(status, '')
        """

        upsert_sql = """
            INSERT INTO payment_analytics.org_txn_monthly_summary (
                org_id, division_id, txn_year, txn_month,
                counterparty_id, counterparty_name, counterparty_type,
                transfer_type, status,
                txn_count, total_amount, total_fees, total_net_amount,
                min_amount, max_amount, avg_amount, refreshed_at
            )
            VALUES (
                %s, %s, %s, %s, %s, %s, %s, %s, %s,
                %s, %s, %s, %s, %s, %s, %s, NOW()
            )
            ON CONFLICT (org_id, division_id, txn_year, txn_month, counterparty_id, transfer_type, status)
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

        src_hook  = PostgresHook(postgres_conn_id=SOURCE_CONN_ID)
        dest_hook = PostgresHook(postgres_conn_id=ANALYTICS_CONN_ID)

        with src_hook.get_conn() as src_conn:
            with src_conn.cursor() as cur:
                cur.execute(fetch_sql, {"month_start": month_start, "month_end": month_end})
                rows = cur.fetchall()

        if not rows:
            log.info("No org transactions found for %s/%s.", month_start.year, month_start.month)
            return 0

        with dest_hook.get_conn() as dest_conn:
            with dest_conn.cursor() as cur:
                cur.executemany(upsert_sql, rows)
            dest_conn.commit()

        with dest_hook.get_conn() as dest_conn:
            with dest_conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE payment_analytics.pipeline_watermarks
                    SET    last_run_at       = NOW(),
                           records_processed = records_processed + %s
                    WHERE  pipeline_name = 'org_monthly_rollup'
                    """,
                    (len(rows),),
                )
            dest_conn.commit()

        log.info("Upserted %d org monthly summary rows.", len(rows))
        return len(rows)

    # -----------------------------------------------------------------------
    # Task 5 — check_if_weekly  (BranchPythonOperator)
    # Runs weekly summary only on Mondays (isoweekday == 1).
    # -----------------------------------------------------------------------
    def _branch_weekly(**context) -> str:
        logical_date: datetime = context["logical_date"]
        if logical_date.isoweekday() == 1:   # Monday
            log.info("Today is Monday — routing to compute_weekly_summary.")
            return "compute_weekly_summary"
        log.info("Not Monday (%s) — skipping weekly summary.", logical_date.strftime("%A"))
        return "skip_weekly_summary"

    check_if_weekly = BranchPythonOperator(
        task_id="check_if_weekly",
        python_callable=_branch_weekly,
    )

    # -----------------------------------------------------------------------
    # Task 6 — compute_weekly_summary  (runs only on Monday)
    # -----------------------------------------------------------------------
    @task(task_id="compute_weekly_summary", trigger_rule="none_failed_min_one_success")
    def compute_weekly_summary(**context) -> None:
        """
        Emit a weekly digest log covering the past 7 days.
        Extend this task to push notifications to Slack / email as needed.
        """
        logical_date: datetime = context["logical_date"]
        week_end   = logical_date.date()
        week_start = week_end - timedelta(days=7)

        log.info(
            "Weekly summary digest | period: %s to %s (UTC)",
            week_start, week_end,
        )

        dest_hook = PostgresHook(postgres_conn_id=ANALYTICS_CONN_ID)
        with dest_hook.get_conn() as conn:
            with conn.cursor() as cur:
                # Customer weekly totals
                cur.execute(
                    """
                    SELECT
                        COUNT(DISTINCT customer_id)  AS unique_customers,
                        SUM(txn_count)               AS total_txns,
                        SUM(total_amount)            AS gross_amount,
                        SUM(total_fees)              AS total_fees,
                        SUM(total_net_amount)        AS net_amount
                    FROM payment_analytics.customer_txn_daily_summary
                    WHERE txn_date >= %(week_start)s
                      AND txn_date  < %(week_end)s
                    """,
                    {"week_start": week_start, "week_end": week_end},
                )
                cust_row = cur.fetchone()

                # Org weekly totals
                cur.execute(
                    """
                    SELECT
                        COUNT(DISTINCT org_id)       AS unique_orgs,
                        SUM(txn_count)               AS total_txns,
                        SUM(total_amount)            AS gross_amount,
                        SUM(total_fees)              AS total_fees,
                        SUM(total_net_amount)        AS net_amount
                    FROM payment_analytics.org_txn_daily_summary
                    WHERE txn_date >= %(week_start)s
                      AND txn_date  < %(week_end)s
                    """,
                    {"week_start": week_start, "week_end": week_end},
                )
                org_row = cur.fetchone()

        log.info(
            "WEEKLY DIGEST | %s to %s | "
            "customers: unique=%s txns=%s gross=%s fees=%s net=%s | "
            "orgs: unique=%s txns=%s gross=%s fees=%s net=%s",
            week_start, week_end,
            cust_row[0], cust_row[1], cust_row[2], cust_row[3], cust_row[4],
            org_row[0],  org_row[1],  org_row[2],  org_row[3],  org_row[4],
        )

    # -----------------------------------------------------------------------
    # Task 7 — skip_weekly_summary (empty node for non-Monday runs)
    # -----------------------------------------------------------------------
    skip_weekly_summary = EmptyOperator(
        task_id="skip_weekly_summary",
    )

    # -----------------------------------------------------------------------
    # Task 8 — data_freshness_check
    # -----------------------------------------------------------------------
    @task(
        task_id="data_freshness_check",
        trigger_rule="none_failed_min_one_success",   # run even if branch skipped
    )
    def data_freshness_check() -> None:
        """
        Verifies that all hourly watermarks have been updated within the last
        2 hours. Logs a WARNING if any pipeline looks stale.
        """
        hook = PostgresHook(postgres_conn_id=ANALYTICS_CONN_ID)
        with hook.get_conn() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT
                        pipeline_name,
                        last_processed_at,
                        EXTRACT(EPOCH FROM (NOW() - last_processed_at)) / 3600.0 AS hours_since_update
                    FROM payment_analytics.pipeline_watermarks
                    ORDER BY hours_since_update DESC
                    """,
                )
                rows = cur.fetchall()

        stale_threshold_hours = 2.0
        stale_pipelines = []

        for pipeline_name, last_processed_at, hours_since in rows:
            if hours_since is None:
                log.warning("Pipeline '%s' has never run (last_processed_at is NULL).", pipeline_name)
                stale_pipelines.append(pipeline_name)
            elif hours_since > stale_threshold_hours:
                log.warning(
                    "STALE PIPELINE | pipeline=%s | last_updated=%s | hours_since=%.2f",
                    pipeline_name, last_processed_at, hours_since,
                )
                stale_pipelines.append(pipeline_name)
            else:
                log.info(
                    "Pipeline '%s' is fresh — last updated %.2f hours ago.",
                    pipeline_name, hours_since,
                )

        if stale_pipelines:
            log.error(
                "Data freshness check FAILED for pipelines: %s. "
                "Investigate Airflow logs and incremental DAG runs.",
                stale_pipelines,
            )
        else:
            log.info("All pipelines are fresh. Freshness check PASSED.")

    # -----------------------------------------------------------------------
    # Wire up the DAG
    # -----------------------------------------------------------------------
    cust_daily   = compute_daily_customer_summary()
    org_daily    = compute_daily_org_summary()
    cust_monthly = compute_monthly_customer_summary()
    org_monthly  = compute_monthly_org_summary()

    # Daily summaries must complete before weekly branch check
    [cust_daily, org_daily, cust_monthly, org_monthly] >> check_if_weekly
    check_if_weekly >> [compute_weekly_summary(), skip_weekly_summary]
    [compute_weekly_summary(), skip_weekly_summary] >> data_freshness_check()


# Instantiate the DAG
payment_daily_rollup()
