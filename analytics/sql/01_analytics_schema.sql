-- =============================================================================
-- 01_analytics_schema.sql
-- Creates the payment_analytics schema and all summary/watermark tables.
-- Target DB: payment_analytics (PostgreSQL)
-- Run order: 1 (run before any DAGs execute)
-- =============================================================================

-- The postgres-analytics container is initialised with POSTGRES_DB=payment_analytics.
-- This script creates the schema objects inside that database.

\connect payment_analytics

-- Also create the superset and airflow databases expected by docker-compose
-- (postgres image multi-db init is handled by init scripts; these are idempotent guards)
CREATE DATABASE airflow WITH OWNER analytics;
CREATE DATABASE superset WITH OWNER analytics;

-- =============================================================================
-- Schema
-- =============================================================================
CREATE SCHEMA IF NOT EXISTS payment_analytics;

SET search_path TO payment_analytics;

-- =============================================================================
-- pipeline_watermarks
-- Tracks the high-water mark for each incremental pipeline so DAGs can
-- resume from exactly where they left off without duplicating rows.
-- =============================================================================
CREATE TABLE IF NOT EXISTS payment_analytics.pipeline_watermarks (
    pipeline_name        VARCHAR(100) PRIMARY KEY,
    last_processed_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT '2020-01-01 00:00:00',
    last_run_at          TIMESTAMP WITHOUT TIME ZONE,
    records_processed    BIGINT NOT NULL DEFAULT 0
);

-- =============================================================================
-- customer_txn_hourly_summary
-- Aggregated per (customer_id, hour, counterparty_id, transfer_type, status).
-- Refreshed every 15 min by the incremental rollup DAG.
-- =============================================================================
CREATE TABLE IF NOT EXISTS payment_analytics.customer_txn_hourly_summary (
    summary_id          BIGSERIAL PRIMARY KEY,
    customer_id         VARCHAR(100)    NOT NULL,
    txn_hour            TIMESTAMP WITHOUT TIME ZONE NOT NULL,  -- DATE_TRUNC('hour', txn_date)
    counterparty_id     VARCHAR(100)    NOT NULL DEFAULT '',
    counterparty_name   VARCHAR(200),
    counterparty_type   VARCHAR(20),
    transfer_type       VARCHAR(30)     NOT NULL DEFAULT '',
    status              VARCHAR(30)     NOT NULL DEFAULT '',
    txn_count           BIGINT          NOT NULL DEFAULT 0,
    total_amount        NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    total_fees          NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    total_net_amount    NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    min_amount          NUMERIC(24, 2),
    max_amount          NUMERIC(24, 2),
    avg_amount          NUMERIC(24, 6),
    refreshed_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cust_hourly UNIQUE (customer_id, txn_hour, counterparty_id, transfer_type, status)
);

CREATE INDEX IF NOT EXISTS idx_cust_hourly_customer_id   ON payment_analytics.customer_txn_hourly_summary (customer_id);
CREATE INDEX IF NOT EXISTS idx_cust_hourly_txn_hour      ON payment_analytics.customer_txn_hourly_summary (txn_hour DESC);
CREATE INDEX IF NOT EXISTS idx_cust_hourly_status        ON payment_analytics.customer_txn_hourly_summary (status);
CREATE INDEX IF NOT EXISTS idx_cust_hourly_transfer_type ON payment_analytics.customer_txn_hourly_summary (transfer_type);
CREATE INDEX IF NOT EXISTS idx_cust_hourly_cust_hour     ON payment_analytics.customer_txn_hourly_summary (customer_id, txn_hour DESC);

-- =============================================================================
-- customer_txn_daily_summary
-- Aggregated per (customer_id, date, counterparty_id, transfer_type, status).
-- Refreshed daily at 1 AM by the daily rollup DAG.
-- =============================================================================
CREATE TABLE IF NOT EXISTS payment_analytics.customer_txn_daily_summary (
    summary_id          BIGSERIAL PRIMARY KEY,
    customer_id         VARCHAR(100)    NOT NULL,
    txn_date            DATE            NOT NULL,
    counterparty_id     VARCHAR(100)    NOT NULL DEFAULT '',
    counterparty_name   VARCHAR(200),
    counterparty_type   VARCHAR(20),
    transfer_type       VARCHAR(30)     NOT NULL DEFAULT '',
    status              VARCHAR(30)     NOT NULL DEFAULT '',
    txn_count           BIGINT          NOT NULL DEFAULT 0,
    total_amount        NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    total_fees          NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    total_net_amount    NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    min_amount          NUMERIC(24, 2),
    max_amount          NUMERIC(24, 2),
    avg_amount          NUMERIC(24, 6),
    refreshed_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cust_daily UNIQUE (customer_id, txn_date, counterparty_id, transfer_type, status)
);

CREATE INDEX IF NOT EXISTS idx_cust_daily_customer_id   ON payment_analytics.customer_txn_daily_summary (customer_id);
CREATE INDEX IF NOT EXISTS idx_cust_daily_txn_date      ON payment_analytics.customer_txn_daily_summary (txn_date DESC);
CREATE INDEX IF NOT EXISTS idx_cust_daily_status        ON payment_analytics.customer_txn_daily_summary (status);
CREATE INDEX IF NOT EXISTS idx_cust_daily_transfer_type ON payment_analytics.customer_txn_daily_summary (transfer_type);
CREATE INDEX IF NOT EXISTS idx_cust_daily_cust_date     ON payment_analytics.customer_txn_daily_summary (customer_id, txn_date DESC);
CREATE INDEX IF NOT EXISTS idx_cust_daily_counterparty  ON payment_analytics.customer_txn_daily_summary (counterparty_id);

-- =============================================================================
-- customer_txn_monthly_summary
-- Aggregated per (customer_id, txn_year, txn_month, counterparty_id,
--                 transfer_type, status).
-- Refreshed daily (month-to-date upsert) by the daily rollup DAG.
-- =============================================================================
CREATE TABLE IF NOT EXISTS payment_analytics.customer_txn_monthly_summary (
    summary_id          BIGSERIAL PRIMARY KEY,
    customer_id         VARCHAR(100)    NOT NULL,
    txn_year            SMALLINT        NOT NULL,
    txn_month           SMALLINT        NOT NULL,
    counterparty_id     VARCHAR(100)    NOT NULL DEFAULT '',
    counterparty_name   VARCHAR(200),
    counterparty_type   VARCHAR(20),
    transfer_type       VARCHAR(30)     NOT NULL DEFAULT '',
    status              VARCHAR(30)     NOT NULL DEFAULT '',
    txn_count           BIGINT          NOT NULL DEFAULT 0,
    total_amount        NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    total_fees          NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    total_net_amount    NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    min_amount          NUMERIC(24, 2),
    max_amount          NUMERIC(24, 2),
    avg_amount          NUMERIC(24, 6),
    refreshed_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_cust_monthly UNIQUE (customer_id, txn_year, txn_month, counterparty_id, transfer_type, status)
);

CREATE INDEX IF NOT EXISTS idx_cust_monthly_customer_id ON payment_analytics.customer_txn_monthly_summary (customer_id);
CREATE INDEX IF NOT EXISTS idx_cust_monthly_year_month  ON payment_analytics.customer_txn_monthly_summary (txn_year DESC, txn_month DESC);
CREATE INDEX IF NOT EXISTS idx_cust_monthly_status      ON payment_analytics.customer_txn_monthly_summary (status);
CREATE INDEX IF NOT EXISTS idx_cust_monthly_cust_ym     ON payment_analytics.customer_txn_monthly_summary (customer_id, txn_year DESC, txn_month DESC);

-- =============================================================================
-- org_txn_hourly_summary
-- =============================================================================
CREATE TABLE IF NOT EXISTS payment_analytics.org_txn_hourly_summary (
    summary_id          BIGSERIAL PRIMARY KEY,
    org_id              VARCHAR(100)    NOT NULL,
    division_id         VARCHAR(100)    NOT NULL DEFAULT '',
    txn_hour            TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    counterparty_id     VARCHAR(100)    NOT NULL DEFAULT '',
    counterparty_name   VARCHAR(200),
    counterparty_type   VARCHAR(20),
    transfer_type       VARCHAR(30)     NOT NULL DEFAULT '',
    status              VARCHAR(30)     NOT NULL DEFAULT '',
    txn_count           BIGINT          NOT NULL DEFAULT 0,
    total_amount        NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    total_fees          NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    total_net_amount    NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    min_amount          NUMERIC(24, 2),
    max_amount          NUMERIC(24, 2),
    avg_amount          NUMERIC(24, 6),
    refreshed_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_org_hourly UNIQUE (org_id, division_id, txn_hour, counterparty_id, transfer_type, status)
);

CREATE INDEX IF NOT EXISTS idx_org_hourly_org_id        ON payment_analytics.org_txn_hourly_summary (org_id);
CREATE INDEX IF NOT EXISTS idx_org_hourly_division_id   ON payment_analytics.org_txn_hourly_summary (division_id);
CREATE INDEX IF NOT EXISTS idx_org_hourly_txn_hour      ON payment_analytics.org_txn_hourly_summary (txn_hour DESC);
CREATE INDEX IF NOT EXISTS idx_org_hourly_status        ON payment_analytics.org_txn_hourly_summary (status);
CREATE INDEX IF NOT EXISTS idx_org_hourly_org_hour      ON payment_analytics.org_txn_hourly_summary (org_id, txn_hour DESC);
CREATE INDEX IF NOT EXISTS idx_org_hourly_div_hour      ON payment_analytics.org_txn_hourly_summary (org_id, division_id, txn_hour DESC);

-- =============================================================================
-- org_txn_daily_summary
-- =============================================================================
CREATE TABLE IF NOT EXISTS payment_analytics.org_txn_daily_summary (
    summary_id          BIGSERIAL PRIMARY KEY,
    org_id              VARCHAR(100)    NOT NULL,
    division_id         VARCHAR(100)    NOT NULL DEFAULT '',
    txn_date            DATE            NOT NULL,
    counterparty_id     VARCHAR(100)    NOT NULL DEFAULT '',
    counterparty_name   VARCHAR(200),
    counterparty_type   VARCHAR(20),
    transfer_type       VARCHAR(30)     NOT NULL DEFAULT '',
    status              VARCHAR(30)     NOT NULL DEFAULT '',
    txn_count           BIGINT          NOT NULL DEFAULT 0,
    total_amount        NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    total_fees          NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    total_net_amount    NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    min_amount          NUMERIC(24, 2),
    max_amount          NUMERIC(24, 2),
    avg_amount          NUMERIC(24, 6),
    refreshed_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_org_daily UNIQUE (org_id, division_id, txn_date, counterparty_id, transfer_type, status)
);

CREATE INDEX IF NOT EXISTS idx_org_daily_org_id         ON payment_analytics.org_txn_daily_summary (org_id);
CREATE INDEX IF NOT EXISTS idx_org_daily_division_id    ON payment_analytics.org_txn_daily_summary (division_id);
CREATE INDEX IF NOT EXISTS idx_org_daily_txn_date       ON payment_analytics.org_txn_daily_summary (txn_date DESC);
CREATE INDEX IF NOT EXISTS idx_org_daily_status         ON payment_analytics.org_txn_daily_summary (status);
CREATE INDEX IF NOT EXISTS idx_org_daily_org_date       ON payment_analytics.org_txn_daily_summary (org_id, txn_date DESC);
CREATE INDEX IF NOT EXISTS idx_org_daily_div_date       ON payment_analytics.org_txn_daily_summary (org_id, division_id, txn_date DESC);
CREATE INDEX IF NOT EXISTS idx_org_daily_counterparty   ON payment_analytics.org_txn_daily_summary (counterparty_id);

-- =============================================================================
-- org_txn_monthly_summary
-- =============================================================================
CREATE TABLE IF NOT EXISTS payment_analytics.org_txn_monthly_summary (
    summary_id          BIGSERIAL PRIMARY KEY,
    org_id              VARCHAR(100)    NOT NULL,
    division_id         VARCHAR(100)    NOT NULL DEFAULT '',
    txn_year            SMALLINT        NOT NULL,
    txn_month           SMALLINT        NOT NULL,
    counterparty_id     VARCHAR(100)    NOT NULL DEFAULT '',
    counterparty_name   VARCHAR(200),
    counterparty_type   VARCHAR(20),
    transfer_type       VARCHAR(30)     NOT NULL DEFAULT '',
    status              VARCHAR(30)     NOT NULL DEFAULT '',
    txn_count           BIGINT          NOT NULL DEFAULT 0,
    total_amount        NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    total_fees          NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    total_net_amount    NUMERIC(24, 2)  NOT NULL DEFAULT 0,
    min_amount          NUMERIC(24, 2),
    max_amount          NUMERIC(24, 2),
    avg_amount          NUMERIC(24, 6),
    refreshed_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_org_monthly UNIQUE (org_id, division_id, txn_year, txn_month, counterparty_id, transfer_type, status)
);

CREATE INDEX IF NOT EXISTS idx_org_monthly_org_id       ON payment_analytics.org_txn_monthly_summary (org_id);
CREATE INDEX IF NOT EXISTS idx_org_monthly_division_id  ON payment_analytics.org_txn_monthly_summary (division_id);
CREATE INDEX IF NOT EXISTS idx_org_monthly_year_month   ON payment_analytics.org_txn_monthly_summary (txn_year DESC, txn_month DESC);
CREATE INDEX IF NOT EXISTS idx_org_monthly_status       ON payment_analytics.org_txn_monthly_summary (status);
CREATE INDEX IF NOT EXISTS idx_org_monthly_org_ym       ON payment_analytics.org_txn_monthly_summary (org_id, txn_year DESC, txn_month DESC);
CREATE INDEX IF NOT EXISTS idx_org_monthly_div_ym       ON payment_analytics.org_txn_monthly_summary (org_id, division_id, txn_year DESC, txn_month DESC);

-- =============================================================================
-- Seed watermark rows for all 6 pipelines
-- ON CONFLICT DO NOTHING makes this idempotent on re-runs.
-- =============================================================================
INSERT INTO payment_analytics.pipeline_watermarks (pipeline_name, last_processed_at, last_run_at, records_processed)
VALUES
    ('customer_hourly_rollup',  '2020-01-01 00:00:00', NULL, 0),
    ('customer_daily_rollup',   '2020-01-01 00:00:00', NULL, 0),
    ('customer_monthly_rollup', '2020-01-01 00:00:00', NULL, 0),
    ('org_hourly_rollup',       '2020-01-01 00:00:00', NULL, 0),
    ('org_daily_rollup',        '2020-01-01 00:00:00', NULL, 0),
    ('org_monthly_rollup',      '2020-01-01 00:00:00', NULL, 0)
ON CONFLICT (pipeline_name) DO NOTHING;
