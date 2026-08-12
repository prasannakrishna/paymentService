-- =============================================================================
-- 02_postgres_partitions.sql
-- PostgreSQL declarative range partitioning for the two source transaction
-- tables, partitioned by txn_date (TIMESTAMP WITHOUT TIME ZONE).
--
-- HOW TO USE:
--   Option A — Manual DDL (recommended for production):
--     1. Before your first deployment, ensure JPA/Hibernate is configured with
--        spring.jpa.hibernate.ddl-auto=validate or none for these two tables.
--     2. Run this script once against the `payment` database as a superuser or
--        the table owner.
--     3. JPA entity classes remain unchanged; Hibernate will see the partitioned
--        table as a regular table and will validate successfully.
--
--   Option B — Replace JPA auto-create:
--     1. Set spring.jpa.hibernate.ddl-auto=none.
--     2. Add this file to your Flyway / Liquibase migration path (e.g., as
--        V1__partitioned_tables.sql) so it runs on first schema creation.
--     3. Remove any @Table DDL annotations that conflict with partitioning.
--
-- PARTITION STRATEGY:
--   - Annual partitions: 2020 through 2026, each covering [Jan 1, Jan 1 next yr).
--   - p_future: open-ended catch-all for dates >= 2027-01-01.
--   - All partitions inherit the same column set, constraints, and indexes as
--     the parent. Unique constraints must include the partition key (txn_date).
--   - Primary key on (txn_id, txn_date) — txn_date added to satisfy PG's
--     requirement that partition key columns be part of any PK/unique constraint.
--
-- NOTE: Run against the `payment` database, NOT payment_analytics.
-- =============================================================================

\connect payment

-- =============================================================================
-- customer_wallet_transactions — partitioned parent
-- =============================================================================

-- Drop and recreate only if you are doing a fresh install.
-- Comment out the DROP lines if the table already has data.
-- DROP TABLE IF EXISTS customer_wallet_transactions CASCADE;

CREATE TABLE IF NOT EXISTS customer_wallet_transactions (
    txn_id              VARCHAR(36)         NOT NULL,
    customer_id         VARCHAR(100)        NOT NULL,
    source_wallet_id    VARCHAR(100),
    transfer_id         VARCHAR(100),
    pg_order_id         VARCHAR(100),
    cf_payment_id       VARCHAR(100),
    bank_reference      VARCHAR(100),
    transfer_type       VARCHAR(30),
    amount              NUMERIC(18, 2)      NOT NULL DEFAULT 0,
    fees                NUMERIC(18, 2)      NOT NULL DEFAULT 0,
    net_amount          NUMERIC(18, 2)      NOT NULL DEFAULT 0,
    currency            VARCHAR(3)          NOT NULL DEFAULT 'INR',
    counterparty_id     VARCHAR(100),
    counterparty_name   VARCHAR(200),
    counterparty_type   VARCHAR(20),
    status              VARCHAR(30),
    txn_date            TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    txn_year_month      INT,
    description         VARCHAR(500),
    -- PK must include partition key column
    PRIMARY KEY (txn_id, txn_date)
)
PARTITION BY RANGE (txn_date);

-- Annual partitions 2020 – 2026
CREATE TABLE IF NOT EXISTS customer_wallet_transactions_p2020
    PARTITION OF customer_wallet_transactions
    FOR VALUES FROM ('2020-01-01 00:00:00') TO ('2021-01-01 00:00:00');

CREATE TABLE IF NOT EXISTS customer_wallet_transactions_p2021
    PARTITION OF customer_wallet_transactions
    FOR VALUES FROM ('2021-01-01 00:00:00') TO ('2022-01-01 00:00:00');

CREATE TABLE IF NOT EXISTS customer_wallet_transactions_p2022
    PARTITION OF customer_wallet_transactions
    FOR VALUES FROM ('2022-01-01 00:00:00') TO ('2023-01-01 00:00:00');

CREATE TABLE IF NOT EXISTS customer_wallet_transactions_p2023
    PARTITION OF customer_wallet_transactions
    FOR VALUES FROM ('2023-01-01 00:00:00') TO ('2024-01-01 00:00:00');

CREATE TABLE IF NOT EXISTS customer_wallet_transactions_p2024
    PARTITION OF customer_wallet_transactions
    FOR VALUES FROM ('2024-01-01 00:00:00') TO ('2025-01-01 00:00:00');

CREATE TABLE IF NOT EXISTS customer_wallet_transactions_p2025
    PARTITION OF customer_wallet_transactions
    FOR VALUES FROM ('2025-01-01 00:00:00') TO ('2026-01-01 00:00:00');

CREATE TABLE IF NOT EXISTS customer_wallet_transactions_p2026
    PARTITION OF customer_wallet_transactions
    FOR VALUES FROM ('2026-01-01 00:00:00') TO ('2027-01-01 00:00:00');

CREATE TABLE IF NOT EXISTS customer_wallet_transactions_p_future
    PARTITION OF customer_wallet_transactions
    FOR VALUES FROM ('2027-01-01 00:00:00') TO (MAXVALUE);

-- Indexes on the partitioned parent propagate to all child partitions.
CREATE INDEX IF NOT EXISTS idx_cwt_customer_id    ON customer_wallet_transactions (customer_id);
CREATE INDEX IF NOT EXISTS idx_cwt_txn_date       ON customer_wallet_transactions (txn_date DESC);
CREATE INDEX IF NOT EXISTS idx_cwt_status         ON customer_wallet_transactions (status);
CREATE INDEX IF NOT EXISTS idx_cwt_transfer_type  ON customer_wallet_transactions (transfer_type);
CREATE INDEX IF NOT EXISTS idx_cwt_transfer_id    ON customer_wallet_transactions (transfer_id) WHERE transfer_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_cwt_txn_year_month ON customer_wallet_transactions (txn_year_month);
CREATE INDEX IF NOT EXISTS idx_cwt_cust_date      ON customer_wallet_transactions (customer_id, txn_date DESC);

-- =============================================================================
-- org_wallet_transactions — partitioned parent
-- =============================================================================

-- DROP TABLE IF EXISTS org_wallet_transactions CASCADE;

CREATE TABLE IF NOT EXISTS org_wallet_transactions (
    txn_id              VARCHAR(36)         NOT NULL,
    org_id              VARCHAR(100)        NOT NULL,
    division_id         VARCHAR(100),
    source_wallet_id    VARCHAR(100),
    transfer_id         VARCHAR(100),
    pg_order_id         VARCHAR(100),
    cf_payment_id       VARCHAR(100),
    bank_reference      VARCHAR(100),
    transfer_type       VARCHAR(30),
    amount              NUMERIC(18, 2)      NOT NULL DEFAULT 0,
    fees                NUMERIC(18, 2)      NOT NULL DEFAULT 0,
    net_amount          NUMERIC(18, 2)      NOT NULL DEFAULT 0,
    currency            VARCHAR(3)          NOT NULL DEFAULT 'INR',
    counterparty_id     VARCHAR(100),
    counterparty_name   VARCHAR(200),
    counterparty_type   VARCHAR(20),
    status              VARCHAR(30),
    txn_date            TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    txn_year_month      INT,
    description         VARCHAR(500),
    PRIMARY KEY (txn_id, txn_date)
)
PARTITION BY RANGE (txn_date);

-- Annual partitions 2020 – 2026
CREATE TABLE IF NOT EXISTS org_wallet_transactions_p2020
    PARTITION OF org_wallet_transactions
    FOR VALUES FROM ('2020-01-01 00:00:00') TO ('2021-01-01 00:00:00');

CREATE TABLE IF NOT EXISTS org_wallet_transactions_p2021
    PARTITION OF org_wallet_transactions
    FOR VALUES FROM ('2021-01-01 00:00:00') TO ('2022-01-01 00:00:00');

CREATE TABLE IF NOT EXISTS org_wallet_transactions_p2022
    PARTITION OF org_wallet_transactions
    FOR VALUES FROM ('2022-01-01 00:00:00') TO ('2023-01-01 00:00:00');

CREATE TABLE IF NOT EXISTS org_wallet_transactions_p2023
    PARTITION OF org_wallet_transactions
    FOR VALUES FROM ('2023-01-01 00:00:00') TO ('2024-01-01 00:00:00');

CREATE TABLE IF NOT EXISTS org_wallet_transactions_p2024
    PARTITION OF org_wallet_transactions
    FOR VALUES FROM ('2024-01-01 00:00:00') TO ('2025-01-01 00:00:00');

CREATE TABLE IF NOT EXISTS org_wallet_transactions_p2025
    PARTITION OF org_wallet_transactions
    FOR VALUES FROM ('2025-01-01 00:00:00') TO ('2026-01-01 00:00:00');

CREATE TABLE IF NOT EXISTS org_wallet_transactions_p2026
    PARTITION OF org_wallet_transactions
    FOR VALUES FROM ('2026-01-01 00:00:00') TO ('2027-01-01 00:00:00');

CREATE TABLE IF NOT EXISTS org_wallet_transactions_p_future
    PARTITION OF org_wallet_transactions
    FOR VALUES FROM ('2027-01-01 00:00:00') TO (MAXVALUE);

-- Indexes on partitioned parent
CREATE INDEX IF NOT EXISTS idx_owt_org_id         ON org_wallet_transactions (org_id);
CREATE INDEX IF NOT EXISTS idx_owt_division_id    ON org_wallet_transactions (division_id) WHERE division_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_owt_txn_date       ON org_wallet_transactions (txn_date DESC);
CREATE INDEX IF NOT EXISTS idx_owt_status         ON org_wallet_transactions (status);
CREATE INDEX IF NOT EXISTS idx_owt_transfer_type  ON org_wallet_transactions (transfer_type);
CREATE INDEX IF NOT EXISTS idx_owt_transfer_id    ON org_wallet_transactions (transfer_id) WHERE transfer_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_owt_txn_year_month ON org_wallet_transactions (txn_year_month);
CREATE INDEX IF NOT EXISTS idx_owt_org_date       ON org_wallet_transactions (org_id, txn_date DESC);
CREATE INDEX IF NOT EXISTS idx_owt_org_div_date   ON org_wallet_transactions (org_id, division_id, txn_date DESC);
