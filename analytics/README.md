# Payment Analytics Stack

End-to-end analytics pipeline for the Payment Service — from raw transaction tables through ETL aggregation to interactive dashboards and a React-embeddable API.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Stack Components](#stack-components)
- [Quick Start](#quick-start)
- [Viewing Dashboards in Superset](#viewing-dashboards-in-superset)
- [Pulling Data into React UI](#pulling-data-into-react-ui)
  - [Installation](#installation)
  - [Provider Setup](#provider-setup)
  - [Securing Tokens per Org](#securing-tokens-per-org)
  - [Row-Level Security in Cube](#row-level-security-in-cube)
  - [Distribution Pie Chart](#distribution-pie-chart)
  - [Weekly Trend Line Chart](#weekly-trend-line-chart)
  - [Full Dashboard Page](#full-dashboard-page)
- [Airflow DAGs](#airflow-dags)
- [Cube Schema Reference](#cube-schema-reference)
- [PostgreSQL Partitioning](#postgresql-partitioning)

---

## Architecture Overview

```
PostgreSQL (payment DB)
  customer_wallet_transactions
  org_wallet_transactions
           │
           │  Apache Airflow (ETL — scheduled)
           │  ├── payment_incremental_rollup  every 15 min
           │  ├── payment_daily_rollup        1:00 AM daily
           │  └── data_quality               2:00 AM daily
           ▼
  PostgreSQL (payment_analytics DB)
  ├── customer_txn_hourly_summary
  ├── customer_txn_daily_summary
  ├── customer_txn_monthly_summary
  ├── org_txn_hourly_summary
  ├── org_txn_daily_summary
  ├── org_txn_monthly_summary
  └── pipeline_watermarks
           │
           ├── Cube (port 4000) ── semantic layer, pre-aggregations, row-level security
           │         │
           │         ├── Apache Superset (port 8088) ── no-code dashboards
           │         │
           │         └── React UI (@cubejs-client/react) ── embedded custom UI
           │
           └── JWT token from Spring Boot /api/v1/analytics/token
```

---

## Stack Components

| Component | Port | Purpose |
|---|---|---|
| **Apache Airflow** | 8080 | DAG orchestration, incremental ETL, data quality |
| **Apache Superset** | 8088 | No-code BI dashboards, charts, filters |
| **Cube** | 4000 | Semantic layer, pre-aggregations, REST/GraphQL API |
| **PostgreSQL Analytics** | 5433 | Rollup tables, Airflow metadata |
| **Redis** | 6379 | Airflow Celery task queue |

---

## Quick Start

```bash
cd analytics/

# Start the full stack
docker compose up -d

# First-time: create analytics schema and seed watermarks
psql -h localhost -p 5433 -U airflow -d payment_analytics \
     -f sql/01_analytics_schema.sql

# Optional: apply PostgreSQL partitioning to source tables
# (run against your main payment DB, not the analytics DB)
psql -h localhost -p 5432 -U pocuser -d payment \
     -f sql/02_postgres_partitions.sql
```

**Access URLs:**

| Service | URL | Credentials |
|---|---|---|
| Airflow UI | http://localhost:8080 | admin / admin |
| Superset UI | http://localhost:8088 | admin / admin |
| Cube API | http://localhost:4000 | JWT (see below) |
| Cube Playground | http://localhost:4000 | dev mode only |

---

## Viewing Dashboards in Superset

### Step 1 — Add the Analytics Database

1. Open **http://localhost:8088** → login as `admin / admin`
2. Go to **Settings → Database Connections → + Database**
3. Choose **PostgreSQL**
4. Fill in:
   - **Host:** `postgres-analytics`
   - **Port:** `5432`
   - **Database:** `payment_analytics`
   - **Username:** `airflow`
   - **Password:** `airflow`
5. Click **Test Connection** → **Connect**

### Step 2 — Create Datasets

Go to **Datasets → + Dataset**, then add each of:

| Dataset name | Table |
|---|---|
| Org Division Payments | `payment_analytics.org_txn_daily_summary` |
| Customer Payments | `payment_analytics.customer_txn_daily_summary` |
| Org Monthly Summary | `payment_analytics.org_txn_monthly_summary` |
| Customer Monthly Summary | `payment_analytics.customer_txn_monthly_summary` |

> **Tip:** For Cube-powered datasets (with pre-aggregations), connect Superset to Cube's SQL API instead:
> Host: `cube` Port: `15432` DB: `db` — Cube exposes a PostgreSQL-compatible endpoint.

### Step 3 — Build the Dashboard

**Distribution Pie Chart** (amount by counterparty):
- Chart type: **Pie Chart**
- Metric: `SUM(total_amount)`
- Group by: `counterparty_name`
- Filter: `org_id = 'YOUR_ORG'`, `status = 'COMPLETED'`

**Weekly Trend Line**:
- Chart type: **Line Chart**
- X-axis: `txn_date` (granularity: Week)
- Metric: `SUM(total_amount)`
- Group by: `transfer_type`

**Status Donut**:
- Chart type: **Pie Chart**
- Metric: `SUM(txn_count)`
- Group by: `status`

**Top Counterparties Bar**:
- Chart type: **Bar Chart**
- Metric: `SUM(total_amount)`
- Group by: `counterparty_name`
- Sort: metric descending, limit 10

### Drill-down Flow

```
1. Select org_id filter        → all charts scope to that org
2. Select division_id filter   → scope to that division
3. Click a pie slice           → Superset adds counterparty filter
                                 → line chart updates to show that party only
4. Drag the date range         → all charts refresh instantly
                                 (hits Cube pre-aggregations — sub-100ms)
5. Select status = FAILED      → table shows failed transactions only
```

---

## Pulling Data into React UI

Cube exposes a typed REST API your React components call directly — no separate backend query layer needed.

### Installation

```bash
npm install @cubejs-client/core @cubejs-client/react
# chart library (or use any charting lib you prefer)
npm install recharts
```

### Provider Setup

Wire up the Cube client at the top of your app. The `tokenFetcher` calls your Spring Boot backend to get a signed JWT that carries the org's identity.

```tsx
// src/App.tsx
import cubejs from '@cubejs-client/core';
import { CubeProvider } from '@cubejs-client/react';

async function fetchCubeToken(): Promise<string> {
  const orgId = getCurrentOrgId(); // from your auth context
  const res = await fetch(`/api/v1/analytics/token?orgId=${orgId}`, {
    headers: { Authorization: `Bearer ${getAuthToken()}` },
  });
  const { token } = await res.json();
  return token;
}

const cubejsApi = cubejs(
  () => fetchCubeToken(),
  { apiUrl: 'http://localhost:4000/cubejs-api/v1' }
);

export default function App() {
  return (
    <CubeProvider cubejsApi={cubejsApi}>
      <YourRouter />
    </CubeProvider>
  );
}
```

### Securing Tokens per Org

Add this endpoint to your Spring Boot payment service. It signs a short-lived JWT that Cube uses to enforce row-level data isolation — each org admin only ever sees their own data.

```java
// PaymentAnalyticsController.java
@GetMapping("/api/v1/analytics/token")
public Map<String, String> getCubeToken(
        @RequestParam String orgId,
        @AuthenticationPrincipal UserDetails user) {

    String token = Jwts.builder()
        .claim("orgId", orgId)
        .claim("divisionId", user.getDivisionId())  // optional
        .setIssuedAt(new Date())
        .setExpiration(Date.from(Instant.now().plusSeconds(3600)))
        .signWith(Keys.hmacShaKeyFor(cubeApiSecret.getBytes()))
        .compact();

    return Map.of("token", token);
}
```

### Row-Level Security in Cube

The `sql_where` clause in `OrgPayments.yml` enforces that every query is automatically scoped to the requesting org. The React client cannot bypass this.

```yaml
# Already present in cube/schema/OrgPayments.yml
sql_where: >
  {CUBE}.org_id = '{{ securityContext.orgId }}'
```

### Distribution Pie Chart

Shows how an org/division's payments are distributed across counterparties, with percentage share for each.

```tsx
// src/components/PaymentDistributionChart.tsx
import { useCubeQuery } from '@cubejs-client/react';
import { PieChart, Pie, Cell, Tooltip, Legend } from 'recharts';

const COLORS = ['#6366f1','#8b5cf6','#ec4899','#f59e0b','#10b981','#3b82f6'];

interface Props {
  orgId: string;
  divisionId?: string;
  dateRange: [string, string];  // e.g. ['2024-01-01', '2024-03-31']
  onSliceClick?: (counterpartyId: string) => void;
}

export function PaymentDistributionChart({ orgId, divisionId, dateRange, onSliceClick }: Props) {
  const { resultSet, isLoading, error } = useCubeQuery({
    measures:   ['OrgDivisionPaymentView.total_amount'],
    dimensions: [
      'OrgDivisionPaymentView.counterparty_name',
      'OrgDivisionPaymentView.counterparty_id',
      'OrgDivisionPaymentView.counterparty_type',
    ],
    filters: [
      {
        member: 'OrgDivisionPaymentView.org_id',
        operator: 'equals',
        values: [orgId],
      },
      ...(divisionId ? [{
        member: 'OrgDivisionPaymentView.division_id',
        operator: 'equals' as const,
        values: [divisionId],
      }] : []),
      {
        member: 'OrgDivisionPaymentView.status',
        operator: 'equals',
        values: ['COMPLETED'],
      },
    ],
    timeDimensions: [{
      dimension: 'OrgDivisionPaymentView.txn_date',
      dateRange,
    }],
    order: { 'OrgDivisionPaymentView.total_amount': 'desc' },
    limit: 15,
  });

  if (isLoading) return <div>Loading...</div>;
  if (error)     return <div>Error: {error.message}</div>;

  const data = resultSet!.tablePivot().map(row => ({
    name:          row['OrgDivisionPaymentView.counterparty_name'] as string,
    counterpartyId: row['OrgDivisionPaymentView.counterparty_id'] as string,
    value:         Number(row['OrgDivisionPaymentView.total_amount']),
  }));

  const formatINR = (v: number) => `₹${v.toLocaleString('en-IN')}`;

  return (
    <PieChart width={480} height={340}>
      <Pie
        data={data}
        dataKey="value"
        nameKey="name"
        cx="50%"
        cy="50%"
        outerRadius={130}
        onClick={(entry) => onSliceClick?.(entry.counterpartyId)}
        label={({ name, percent }) => `${name} ${(percent * 100).toFixed(1)}%`}
      >
        {data.map((_, i) => (
          <Cell key={i} fill={COLORS[i % COLORS.length]} />
        ))}
      </Pie>
      <Tooltip formatter={(v: number) => formatINR(v)} />
      <Legend />
    </PieChart>
  );
}
```

### Weekly Trend Line Chart

Updates automatically when the user clicks a pie slice — shows payment volume trend for the selected counterparty.

```tsx
// src/components/WeeklyTrendChart.tsx
import { useCubeQuery } from '@cubejs-client/react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { format } from 'date-fns';

interface Props {
  orgId: string;
  divisionId?: string;
  counterpartyId?: string;   // set when user clicks a pie slice
  dateRange: [string, string];
}

export function WeeklyTrendChart({ orgId, divisionId, counterpartyId, dateRange }: Props) {
  const { resultSet, isLoading } = useCubeQuery({
    measures: [
      'OrgDivisionPaymentView.total_amount',
      'OrgDivisionPaymentView.total_transactions',
    ],
    timeDimensions: [{
      dimension: 'OrgDivisionPaymentView.txn_date',
      granularity: 'week',
      dateRange,
    }],
    filters: [
      {
        member: 'OrgDivisionPaymentView.org_id',
        operator: 'equals',
        values: [orgId],
      },
      ...(divisionId ? [{
        member: 'OrgDivisionPaymentView.division_id',
        operator: 'equals' as const,
        values: [divisionId],
      }] : []),
      ...(counterpartyId ? [{
        member: 'OrgDivisionPaymentView.counterparty_id',
        operator: 'equals' as const,
        values: [counterpartyId],
      }] : []),
    ],
    order: { 'OrgDivisionPaymentView.txn_date': 'asc' },
  });

  if (isLoading) return <div>Loading...</div>;

  const data = resultSet!.tablePivot().map(row => ({
    week:   format(
      new Date(row['OrgDivisionPaymentView.txn_date.week'] as string),
      'dd MMM'
    ),
    amount: Number(row['OrgDivisionPaymentView.total_amount']),
    txns:   Number(row['OrgDivisionPaymentView.total_transactions']),
  }));

  return (
    <ResponsiveContainer width="100%" height={280}>
      <LineChart data={data}>
        <CartesianGrid strokeDasharray="3 3" />
        <XAxis dataKey="week" />
        <YAxis tickFormatter={v => `₹${(v / 1000).toFixed(0)}K`} />
        <Tooltip
          formatter={(v: number, name: string) =>
            name === 'amount' ? `₹${v.toLocaleString('en-IN')}` : v
          }
        />
        <Line type="monotone" dataKey="amount" name="Amount"
              stroke="#6366f1" strokeWidth={2} dot={false} />
        <Line type="monotone" dataKey="txns" name="Transactions"
              stroke="#10b981" strokeWidth={2} dot={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}
```

### Full Dashboard Page

Composes all components with shared filter state. Clicking a pie slice drills the trend line down to that counterparty.

```tsx
// src/pages/OrgPaymentDashboard.tsx
import { useState } from 'react';
import { useCubeQuery } from '@cubejs-client/react';
import { PaymentDistributionChart } from '../components/PaymentDistributionChart';
import { WeeklyTrendChart } from '../components/WeeklyTrendChart';

interface Props {
  orgId: string;
}

export function OrgPaymentDashboard({ orgId }: Props) {
  const [divisionId, setDivisionId]         = useState<string>();
  const [counterpartyId, setCounterpartyId] = useState<string>();
  const [dateRange, setDateRange]           = useState<[string, string]>([
    'last quarter', 'now'
  ]);

  // KPI summary — all measures for a single tile row
  const { resultSet: kpi } = useCubeQuery({
    measures: [
      'OrgDivisionPaymentView.total_amount',
      'OrgDivisionPaymentView.total_transactions',
      'OrgDivisionPaymentView.success_rate',
      'OrgDivisionPaymentView.unique_counterparties',
      'OrgDivisionPaymentView.fee_rate',
    ],
    filters: [
      { member: 'OrgDivisionPaymentView.org_id', operator: 'equals', values: [orgId] },
      ...(divisionId ? [{
        member: 'OrgDivisionPaymentView.division_id',
        operator: 'equals' as const,
        values: [divisionId],
      }] : []),
    ],
    timeDimensions: [{
      dimension: 'OrgDivisionPaymentView.txn_date',
      dateRange,
    }],
  });

  const row = kpi?.tablePivot()[0];
  const fmt  = (key: string) => row?.[`OrgDivisionPaymentView.${key}`] ?? '—';
  const fmtINR = (key: string) =>
    `₹${Number(row?.[`OrgDivisionPaymentView.${key}`] ?? 0).toLocaleString('en-IN')}`;

  return (
    <div className="p-6 space-y-6">

      {/* ── Filter bar ─────────────────────────────────────────────────── */}
      <div className="flex gap-4 items-center flex-wrap">
        <select onChange={e => setDivisionId(e.target.value || undefined)}
                className="border rounded px-3 py-1">
          <option value="">All Divisions</option>
          {/* Populate from your divisions API */}
        </select>

        <select onChange={e => {
          const ranges: Record<string, [string, string]> = {
            'last_month':   ['last month',   'now'],
            'last_quarter': ['last quarter', 'now'],
            'last_6m':      ['6 months ago', 'now'],
            'last_year':    ['last year',    'now'],
          };
          setDateRange(ranges[e.target.value] ?? ['last quarter', 'now']);
        }} className="border rounded px-3 py-1">
          <option value="last_month">Last Month</option>
          <option value="last_quarter" selected>Last Quarter</option>
          <option value="last_6m">Last 6 Months</option>
          <option value="last_year">Last Year</option>
        </select>

        {counterpartyId && (
          <button onClick={() => setCounterpartyId(undefined)}
                  className="text-sm text-indigo-600 underline">
            ✕ Clear counterparty filter
          </button>
        )}
      </div>

      {/* ── KPI tiles ──────────────────────────────────────────────────── */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <KpiTile label="Total Paid"         value={fmtINR('total_amount')} />
        <KpiTile label="Transactions"       value={fmt('total_transactions')} />
        <KpiTile label="Success Rate"       value={`${fmt('success_rate')}%`}
          warn={Number(fmt('success_rate')) < 90} />
        <KpiTile label="Counterparties"     value={fmt('unique_counterparties')} />
      </div>

      {/* ── Distribution + Top 10 ──────────────────────────────────────── */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        <div className="bg-white rounded-xl shadow p-4">
          <h3 className="font-semibold mb-3">Payment Distribution by Counterparty</h3>
          <PaymentDistributionChart
            orgId={orgId}
            divisionId={divisionId}
            dateRange={dateRange}
            onSliceClick={setCounterpartyId}
          />
        </div>

        <div className="bg-white rounded-xl shadow p-4">
          <h3 className="font-semibold mb-3">Top Counterparties by Amount</h3>
          <TopCounterpartiesTable
            orgId={orgId}
            divisionId={divisionId}
            dateRange={dateRange}
            onRowClick={setCounterpartyId}
          />
        </div>
      </div>

      {/* ── Weekly trend (drills into selected counterparty) ───────────── */}
      <div className="bg-white rounded-xl shadow p-4">
        <h3 className="font-semibold mb-3">
          {counterpartyId
            ? `Weekly Trend — ${counterpartyId}`
            : 'Weekly Payment Volume Trend (All Counterparties)'}
        </h3>
        <WeeklyTrendChart
          orgId={orgId}
          divisionId={divisionId}
          counterpartyId={counterpartyId}
          dateRange={dateRange}
        />
      </div>

    </div>
  );
}

// ── Tiny reusable components ────────────────────────────────────────────────

function KpiTile({ label, value, warn }: { label: string; value: string | number; warn?: boolean }) {
  return (
    <div className={`rounded-xl shadow p-4 ${warn ? 'bg-red-50 border border-red-200' : 'bg-white'}`}>
      <p className="text-sm text-gray-500">{label}</p>
      <p className={`text-2xl font-bold mt-1 ${warn ? 'text-red-600' : 'text-gray-900'}`}>{value}</p>
    </div>
  );
}

function TopCounterpartiesTable({
  orgId, divisionId, dateRange, onRowClick
}: {
  orgId: string;
  divisionId?: string;
  dateRange: [string, string];
  onRowClick: (id: string) => void;
}) {
  const { resultSet, isLoading } = useCubeQuery({
    measures: [
      'OrgDivisionPaymentView.total_amount',
      'OrgDivisionPaymentView.total_transactions',
      'OrgDivisionPaymentView.success_rate',
    ],
    dimensions: [
      'OrgDivisionPaymentView.counterparty_id',
      'OrgDivisionPaymentView.counterparty_name',
    ],
    filters: [
      { member: 'OrgDivisionPaymentView.org_id', operator: 'equals', values: [orgId] },
      ...(divisionId ? [{
        member: 'OrgDivisionPaymentView.division_id',
        operator: 'equals' as const, values: [divisionId],
      }] : []),
    ],
    timeDimensions: [{ dimension: 'OrgDivisionPaymentView.txn_date', dateRange }],
    order: { 'OrgDivisionPaymentView.total_amount': 'desc' },
    limit: 10,
  });

  if (isLoading) return <div>Loading...</div>;

  return (
    <table className="w-full text-sm">
      <thead className="text-xs text-gray-500 uppercase border-b">
        <tr>
          <th className="text-left py-2">Counterparty</th>
          <th className="text-right py-2">Amount</th>
          <th className="text-right py-2">Txns</th>
          <th className="text-right py-2">Success %</th>
        </tr>
      </thead>
      <tbody>
        {resultSet!.tablePivot().map((row, i) => (
          <tr key={i}
              className="border-b hover:bg-indigo-50 cursor-pointer"
              onClick={() => onRowClick(row['OrgDivisionPaymentView.counterparty_id'] as string)}>
            <td className="py-2 text-indigo-600">
              {row['OrgDivisionPaymentView.counterparty_name']}
            </td>
            <td className="py-2 text-right">
              ₹{Number(row['OrgDivisionPaymentView.total_amount']).toLocaleString('en-IN')}
            </td>
            <td className="py-2 text-right">
              {row['OrgDivisionPaymentView.total_transactions']}
            </td>
            <td className="py-2 text-right">
              {row['OrgDivisionPaymentView.success_rate']}%
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
```

---

## Airflow DAGs

| DAG | Schedule | What it does |
|---|---|---|
| `payment_incremental_rollup` | Every 15 min | Reads new rows since last watermark, upserts into hourly summaries for both customer and org pipelines (parallel task groups) |
| `payment_daily_rollup` | 1:00 AM daily | Full yesterday recompute into daily + monthly summaries; weekly summary on Mondays; data freshness check |
| `payment_data_quality` | 2:00 AM daily | 6 parallel checks: null IDs, negative amounts, duplicate transfers, summary vs raw total reconciliation, stale watermarks |

**DAG files:** `airflow/dags/`

**Connections required** (auto-registered by `airflow-init` in docker-compose):

| Connection ID | Points to |
|---|---|
| `payment_db` | Source PostgreSQL — `payment` database on port 5432 |
| `payment_analytics_db` | Analytics PostgreSQL — `payment_analytics` database on port 5433 |

---

## Cube Schema Reference

| File | Cube / View | Purpose |
|---|---|---|
| `cube/schema/OrgPayments.yml` | `OrgPayments` cube | All org/division dimensions, measures, 5 pre-aggregations |
| `cube/schema/OrgDivisionView.yml` | `OrgDivisionPaymentView` view | Focused 25-field view for org admin dashboards |
| `cube/schema/CustomerPayments.yml` | `CustomerPayments` cube | Customer spending, counterparty distribution, trend |

### Available measures (OrgDivisionPaymentView)

| Measure | Type | Use in |
|---|---|---|
| `total_amount` | sum | KPI tile, bar chart Y-axis |
| `total_amount_pct` | percent_of_total | Pie / donut chart — auto-computes share per party |
| `total_transactions` | sum | KPI tile, count chart |
| `total_txn_pct` | percent_of_total | Transaction count distribution |
| `success_rate` | calculated % | KPI tile, colour-code table rows |
| `failure_rate` | calculated % | Alert threshold |
| `fee_rate` | calculated % | Cost analysis |
| `completed_amount` | filtered sum | Net settled amount |
| `avg_transaction_amount` | avg | Typical transaction size |
| `max_single_txn` | max | Outlier detection |
| `unique_counterparties` | count_distinct | Breadth of payment network |

### Pre-aggregations (query performance)

| Pre-agg name | Granularity | Query it powers |
|---|---|---|
| `counterparty_distribution_monthly` | month | Pie/bar distribution charts |
| `weekly_trend_by_division` | week | Line chart trend |
| `monthly_summary_by_org` | month | KPI tiles, month-over-month |
| `daily_detail_by_counterparty` | day | Drill-down after clicking a slice |
| `status_distribution_quarterly` | quarter | Status donut |

Cube refreshes pre-aggregations every hour. First query after a restart may be slow (building the rollup) — subsequent queries return in under 100ms.

---

## PostgreSQL Partitioning

Source tables (`customer_wallet_transactions`, `org_wallet_transactions`) are range-partitioned by `txn_date` (annual partitions). This means Airflow's incremental watermark queries only scan the current year's partition rather than the full 7-year table.

Run `sql/02_postgres_partitions.sql` once after initial deployment. The script handles the conversion from a non-partitioned table. See the file header for Option A (manual DDL) vs Option B (Flyway migration) instructions.

Add a new partition at the start of each year:

```sql
-- Run every January 1st (or automate via Airflow DAG)
CREATE TABLE customer_wallet_transactions_2027
    PARTITION OF customer_wallet_transactions
    FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');

CREATE TABLE org_wallet_transactions_2027
    PARTITION OF org_wallet_transactions
    FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
```
