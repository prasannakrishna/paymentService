"""
data_quality_dag.py
====================
Runs nightly at 2 AM and performs a suite of data quality checks against
both the source payment DB and the analytics DB.

Checks:
  1. check_null_customer_ids     — rows with NULL customer_id in source
  2. check_null_org_ids          — rows with NULL org_id in source
  3. check_negative_amounts      — rows with amount < 0 in source
  4. check_duplicate_transfers   — transfer_ids with count > 1 in source
  5. check_summary_vs_raw_totals — daily summary totals vs raw source for yesterday
  6. check_stale_watermarks      — any watermark older than 1 hour
  7. generate_quality_report     — aggregates all check results and logs a report

Design principles:
  - continue_on_failure: every check task catches its own exceptions, stores the
    result in XCom, and lets the pipeline continue.  The final report task reads
    all XCom results and marks itself as FAILED if any check found issues.
  - All DB access uses PostgresHook.get_conn() with explicit cursors so we can
    pass query parameters safely (no SQL injection via string formatting).

Airflow Connections required:
  - payment_db           : source PostgreSQL (DB: payment)
  - payment_analytics_db : analytics PostgreSQL (DB: payment_analytics)
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, date
from typing import Any

from airflow.decorators import dag, task
from airflow.providers.postgres.hooks.postgres import PostgresHook

log = logging.getLogger(__name__)

SOURCE_CONN_ID    = "payment_db"
ANALYTICS_CONN_ID = "payment_analytics_db"

# A check result dict always has these keys:
#   check_name   : str
#   status       : "PASS" | "FAIL" | "ERROR"
#   detail       : str | dict   (human-readable detail)
#   error        : str | None   (exception message if status == "ERROR")

DEFAULT_ARGS = {
    "owner": "analytics",
    "retries": 1,
    "retry_delay": timedelta(minutes=3),
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
    dag_id="payment_data_quality",
    description="Nightly data quality checks for payment transaction tables",
    schedule="0 2 * * *",
    start_date=datetime(2024, 1, 1),
    catchup=False,
    max_active_runs=1,
    default_args=DEFAULT_ARGS,
    on_failure_callback=_on_failure_callback,
    tags=["payment", "data-quality"],
    doc_md="""
    ## Payment Data Quality DAG
    Runs a battery of quality checks across the source and analytics databases.
    All individual check tasks continue even if siblings fail. The final
    `generate_quality_report` task aggregates results and raises an exception
    if any check is in FAIL or ERROR state, which marks the DAG run as failed.
    """,
)
def payment_data_quality():

    # -----------------------------------------------------------------------
    # Helper — execute a count query safely and return the integer result
    # -----------------------------------------------------------------------
    def _count_query(hook: PostgresHook, sql: str, params: tuple | None = None) -> int:
        with hook.get_conn() as conn:
            with conn.cursor() as cur:
                cur.execute(sql, params)
                row = cur.fetchone()
        return int(row[0]) if row else 0

    # -----------------------------------------------------------------------
    # Check 1 — NULL customer IDs
    # -----------------------------------------------------------------------
    @task(task_id="check_null_customer_ids")
    def check_null_customer_ids() -> dict:
        check_name = "null_customer_ids"
        try:
            hook = PostgresHook(postgres_conn_id=SOURCE_CONN_ID)
            count = _count_query(
                hook,
                "SELECT COUNT(*) FROM customer_wallet_transactions WHERE customer_id IS NULL",
            )
            if count > 0:
                log.error(
                    "DATA QUALITY FAIL | check=%s | null_rows=%d",
                    check_name, count,
                )
                return {"check_name": check_name, "status": "FAIL",
                        "detail": {"null_customer_id_count": count}, "error": None}
            log.info("check=%s PASS — no NULL customer_ids found.", check_name)
            return {"check_name": check_name, "status": "PASS",
                    "detail": {"null_customer_id_count": 0}, "error": None}
        except Exception as exc:
            log.error("check=%s ERROR | %s", check_name, exc, exc_info=True)
            return {"check_name": check_name, "status": "ERROR",
                    "detail": {}, "error": str(exc)}

    # -----------------------------------------------------------------------
    # Check 2 — NULL org IDs
    # -----------------------------------------------------------------------
    @task(task_id="check_null_org_ids")
    def check_null_org_ids() -> dict:
        check_name = "null_org_ids"
        try:
            hook = PostgresHook(postgres_conn_id=SOURCE_CONN_ID)
            count = _count_query(
                hook,
                "SELECT COUNT(*) FROM org_wallet_transactions WHERE org_id IS NULL",
            )
            if count > 0:
                log.error(
                    "DATA QUALITY FAIL | check=%s | null_rows=%d",
                    check_name, count,
                )
                return {"check_name": check_name, "status": "FAIL",
                        "detail": {"null_org_id_count": count}, "error": None}
            log.info("check=%s PASS — no NULL org_ids found.", check_name)
            return {"check_name": check_name, "status": "PASS",
                    "detail": {"null_org_id_count": 0}, "error": None}
        except Exception as exc:
            log.error("check=%s ERROR | %s", check_name, exc, exc_info=True)
            return {"check_name": check_name, "status": "ERROR",
                    "detail": {}, "error": str(exc)}

    # -----------------------------------------------------------------------
    # Check 3 — Negative amounts
    # -----------------------------------------------------------------------
    @task(task_id="check_negative_amounts")
    def check_negative_amounts() -> dict:
        check_name = "negative_amounts"
        try:
            hook = PostgresHook(postgres_conn_id=SOURCE_CONN_ID)

            cust_neg = _count_query(
                hook,
                "SELECT COUNT(*) FROM customer_wallet_transactions WHERE amount < 0",
            )
            org_neg = _count_query(
                hook,
                "SELECT COUNT(*) FROM org_wallet_transactions WHERE amount < 0",
            )
            total_neg = cust_neg + org_neg
            detail = {
                "customer_negative_amount_count": cust_neg,
                "org_negative_amount_count": org_neg,
            }

            if total_neg > 0:
                log.error(
                    "DATA QUALITY FAIL | check=%s | customer_neg=%d | org_neg=%d",
                    check_name, cust_neg, org_neg,
                )
                return {"check_name": check_name, "status": "FAIL",
                        "detail": detail, "error": None}

            log.info("check=%s PASS — no negative amounts found.", check_name)
            return {"check_name": check_name, "status": "PASS",
                    "detail": detail, "error": None}
        except Exception as exc:
            log.error("check=%s ERROR | %s", check_name, exc, exc_info=True)
            return {"check_name": check_name, "status": "ERROR",
                    "detail": {}, "error": str(exc)}

    # -----------------------------------------------------------------------
    # Check 4 — Duplicate transfer IDs
    # -----------------------------------------------------------------------
    @task(task_id="check_duplicate_transfers")
    def check_duplicate_transfers() -> dict:
        check_name = "duplicate_transfers"
        try:
            hook = PostgresHook(postgres_conn_id=SOURCE_CONN_ID)

            cust_dup_sql = """
                SELECT COUNT(*) FROM (
                    SELECT transfer_id
                    FROM   customer_wallet_transactions
                    WHERE  transfer_id IS NOT NULL
                    GROUP BY transfer_id
                    HAVING COUNT(*) > 1
                ) AS dup_cust
            """
            org_dup_sql = """
                SELECT COUNT(*) FROM (
                    SELECT transfer_id
                    FROM   org_wallet_transactions
                    WHERE  transfer_id IS NOT NULL
                    GROUP BY transfer_id
                    HAVING COUNT(*) > 1
                ) AS dup_org
            """

            cust_dup_count = _count_query(hook, cust_dup_sql)
            org_dup_count  = _count_query(hook, org_dup_sql)
            total_dup      = cust_dup_count + org_dup_count

            detail = {
                "customer_duplicate_transfer_ids": cust_dup_count,
                "org_duplicate_transfer_ids": org_dup_count,
            }

            if total_dup > 0:
                # Log the actual duplicate transfer_ids (limited to 20 for readability)
                with hook.get_conn() as conn:
                    with conn.cursor() as cur:
                        cur.execute(
                            """
                            SELECT transfer_id, COUNT(*) AS cnt
                            FROM   customer_wallet_transactions
                            WHERE  transfer_id IS NOT NULL
                            GROUP BY transfer_id
                            HAVING COUNT(*) > 1
                            ORDER BY cnt DESC
                            LIMIT 20
                            """,
                        )
                        cust_examples = cur.fetchall()
                log.error(
                    "DATA QUALITY FAIL | check=%s | customer_dups=%d | org_dups=%d | "
                    "customer_examples=%s",
                    check_name, cust_dup_count, org_dup_count, cust_examples,
                )
                detail["customer_examples"] = [
                    {"transfer_id": r[0], "count": r[1]} for r in cust_examples
                ]
                return {"check_name": check_name, "status": "FAIL",
                        "detail": detail, "error": None}

            log.info("check=%s PASS — no duplicate transfer_ids found.", check_name)
            return {"check_name": check_name, "status": "PASS",
                    "detail": detail, "error": None}
        except Exception as exc:
            log.error("check=%s ERROR | %s", check_name, exc, exc_info=True)
            return {"check_name": check_name, "status": "ERROR",
                    "detail": {}, "error": str(exc)}

    # -----------------------------------------------------------------------
    # Check 5 — Summary totals vs raw source for yesterday
    # -----------------------------------------------------------------------
    @task(task_id="check_summary_vs_raw_totals")
    def check_summary_vs_raw_totals(**context) -> dict:
        """
        Compare SUM(amount) in the daily summary table against SUM(amount) in
        the raw source table for yesterday.  Fail if the absolute difference
        exceeds 0.01 (rounding tolerance).
        """
        check_name = "summary_vs_raw_totals"
        try:
            logical_date: datetime = context["logical_date"]
            yesterday: date = (logical_date - timedelta(days=1)).date()
            today: date     = logical_date.date()

            src_hook  = PostgresHook(postgres_conn_id=SOURCE_CONN_ID)
            dest_hook = PostgresHook(postgres_conn_id=ANALYTICS_CONN_ID)

            # Raw totals from source
            raw_cust_total_sql = """
                SELECT COALESCE(SUM(amount), 0)
                FROM   customer_wallet_transactions
                WHERE  txn_date >= %(day_start)s
                  AND  txn_date  < %(day_end)s
            """
            raw_org_total_sql = """
                SELECT COALESCE(SUM(amount), 0)
                FROM   org_wallet_transactions
                WHERE  txn_date >= %(day_start)s
                  AND  txn_date  < %(day_end)s
            """
            params = {"day_start": yesterday, "day_end": today}

            with src_hook.get_conn() as conn:
                with conn.cursor() as cur:
                    cur.execute(raw_cust_total_sql, params)
                    raw_cust_total = float(cur.fetchone()[0])

                    cur.execute(raw_org_total_sql, params)
                    raw_org_total = float(cur.fetchone()[0])

            # Summary totals from analytics
            summary_cust_sql = """
                SELECT COALESCE(SUM(total_amount), 0)
                FROM   payment_analytics.customer_txn_daily_summary
                WHERE  txn_date = %(yesterday)s
            """
            summary_org_sql = """
                SELECT COALESCE(SUM(total_amount), 0)
                FROM   payment_analytics.org_txn_daily_summary
                WHERE  txn_date = %(yesterday)s
            """

            with dest_hook.get_conn() as conn:
                with conn.cursor() as cur:
                    cur.execute(summary_cust_sql, {"yesterday": yesterday})
                    summary_cust_total = float(cur.fetchone()[0])

                    cur.execute(summary_org_sql, {"yesterday": yesterday})
                    summary_org_total = float(cur.fetchone()[0])

            cust_diff = abs(raw_cust_total - summary_cust_total)
            org_diff  = abs(raw_org_total  - summary_org_total)
            tolerance = 0.01

            detail = {
                "date": str(yesterday),
                "raw_customer_total": raw_cust_total,
                "summary_customer_total": summary_cust_total,
                "customer_diff": cust_diff,
                "raw_org_total": raw_org_total,
                "summary_org_total": summary_org_total,
                "org_diff": org_diff,
                "tolerance": tolerance,
            }

            if cust_diff > tolerance or org_diff > tolerance:
                log.error(
                    "DATA QUALITY FAIL | check=%s | date=%s | "
                    "cust_diff=%.4f | org_diff=%.4f | tolerance=%.4f",
                    check_name, yesterday, cust_diff, org_diff, tolerance,
                )
                return {"check_name": check_name, "status": "FAIL",
                        "detail": detail, "error": None}

            log.info(
                "check=%s PASS | date=%s | cust_diff=%.6f | org_diff=%.6f",
                check_name, yesterday, cust_diff, org_diff,
            )
            return {"check_name": check_name, "status": "PASS",
                    "detail": detail, "error": None}

        except Exception as exc:
            log.error("check=%s ERROR | %s", check_name, exc, exc_info=True)
            return {"check_name": check_name, "status": "ERROR",
                    "detail": {}, "error": str(exc)}

    # -----------------------------------------------------------------------
    # Check 6 — Stale watermarks
    # -----------------------------------------------------------------------
    @task(task_id="check_stale_watermarks")
    def check_stale_watermarks() -> dict:
        """
        Alert if any pipeline's watermark has not been updated within the
        last 1 hour.  Applies to all 6 pipelines.
        """
        check_name = "stale_watermarks"
        stale_threshold_hours = 1.0
        try:
            hook = PostgresHook(postgres_conn_id=ANALYTICS_CONN_ID)
            with hook.get_conn() as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        SELECT
                            pipeline_name,
                            last_processed_at,
                            last_run_at,
                            EXTRACT(EPOCH FROM (NOW() - last_processed_at)) / 3600.0 AS hours_since_update
                        FROM payment_analytics.pipeline_watermarks
                        ORDER BY hours_since_update DESC NULLS FIRST
                        """,
                    )
                    rows = cur.fetchall()

            stale = []
            fresh = []
            for pipeline_name, last_processed_at, last_run_at, hours_since in rows:
                entry = {
                    "pipeline_name": pipeline_name,
                    "last_processed_at": str(last_processed_at),
                    "last_run_at": str(last_run_at),
                    "hours_since_update": round(float(hours_since), 3) if hours_since is not None else None,
                }
                if hours_since is None or float(hours_since) > stale_threshold_hours:
                    stale.append(entry)
                    log.error(
                        "STALE WATERMARK | pipeline=%s | last_updated=%s | hours_since=%.2f",
                        pipeline_name, last_processed_at,
                        float(hours_since) if hours_since is not None else -1,
                    )
                else:
                    fresh.append(entry)

            detail = {
                "stale_pipelines": stale,
                "fresh_pipelines": fresh,
                "threshold_hours": stale_threshold_hours,
            }

            if stale:
                return {"check_name": check_name, "status": "FAIL",
                        "detail": detail, "error": None}

            log.info("check=%s PASS — all %d pipelines are fresh.", check_name, len(fresh))
            return {"check_name": check_name, "status": "PASS",
                    "detail": detail, "error": None}

        except Exception as exc:
            log.error("check=%s ERROR | %s", check_name, exc, exc_info=True)
            return {"check_name": check_name, "status": "ERROR",
                    "detail": {}, "error": str(exc)}

    # -----------------------------------------------------------------------
    # Task 7 — generate_quality_report
    # -----------------------------------------------------------------------
    @task(task_id="generate_quality_report", trigger_rule="all_done")
    def generate_quality_report(
        null_cust_result: dict,
        null_org_result: dict,
        neg_amount_result: dict,
        dup_transfer_result: dict,
        summary_vs_raw_result: dict,
        stale_watermark_result: dict,
    ) -> None:
        """
        Aggregates all check results into a structured quality report and logs
        it as a single INFO record.  Raises an exception to mark the DAG run as
        FAILED if any check is in FAIL or ERROR state.
        """
        all_results = [
            null_cust_result,
            null_org_result,
            neg_amount_result,
            dup_transfer_result,
            summary_vs_raw_result,
            stale_watermark_result,
        ]

        failed_checks = [r for r in all_results if r.get("status") in ("FAIL", "ERROR")]
        passed_checks = [r for r in all_results if r.get("status") == "PASS"]

        report = {
            "run_at": datetime.utcnow().isoformat() + "Z",
            "total_checks": len(all_results),
            "passed": len(passed_checks),
            "failed": len(failed_checks),
            "checks": all_results,
        }

        if failed_checks:
            log.error("DATA QUALITY REPORT (FAILED) | %s", report)
            failed_names = [r["check_name"] for r in failed_checks]
            raise ValueError(
                f"Data quality checks failed: {failed_names}. "
                f"See logs above for details."
            )

        log.info("DATA QUALITY REPORT (ALL PASSED) | %s", report)

    # -----------------------------------------------------------------------
    # Wire up the DAG — all checks run in parallel, report collects results
    # -----------------------------------------------------------------------
    null_cust_result      = check_null_customer_ids()
    null_org_result       = check_null_org_ids()
    neg_amount_result     = check_negative_amounts()
    dup_transfer_result   = check_duplicate_transfers()
    summary_vs_raw_result = check_summary_vs_raw_totals()
    stale_wm_result       = check_stale_watermarks()

    generate_quality_report(
        null_cust_result=null_cust_result,
        null_org_result=null_org_result,
        neg_amount_result=neg_amount_result,
        dup_transfer_result=dup_transfer_result,
        summary_vs_raw_result=summary_vs_raw_result,
        stale_watermark_result=stale_wm_result,
    )


# Instantiate the DAG
payment_data_quality()
