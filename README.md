# paymentService

Customer-facing payment processing and settlement service. Handles the money movement from customers to the platform and from the platform to sellers/partners.

---

## What It Does

Two distinct flows:

1. **Customer Payments (Collection)** — customer pays for their order at checkout or via subscription billing
2. **Partner Settlements (Payout)** — platform pays sellers, carriers, warehouses, delivery partners based on financeService invoices

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         paymentService                                    │
│                                                                          │
│   COLLECTION SIDE                    │          SETTLEMENT SIDE           │
│   (customer → platform)              │          (platform → partner)      │
│                                      │                                    │
│   Customer checkout                  │   Invoice APPROVED in financeService│
│     → Payment intent                 │     → Settlement batch created     │
│     → Gateway charge                 │     → Payout initiated             │
│     → Confirmation                   │     → Bank transfer executed       │
│     → Order released                 │     → Reconciliation               │
│                                      │                                    │
│   Subscription renewal               │   Commission deduction             │
│     → Auto-charge saved method       │     → Net amount calculated        │
│     → Retry on failure               │     → TDS/GST withheld             │
│     → Dunning on exhaustion          │     → Payout disbursed             │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Customer Payment Flow (Collection)

### Step 1: Payment Intent

When a customer checks out (or a subscription renews), orderService/cartService creates a **PaymentIntent**:

```json
POST /api/v1/payments/intents
{
  "orderId": "ORD-2026-123456",
  "customerId": "cust-001",
  "amount": 849.00,
  "currency": "INR",
  "orderType": "COMMUNITY_ORDER",     // or DIRECT_ORDER, SUBSCRIPTION_RENEWAL
  "items": [
    { "productId": "prod-mango-001", "sellerId": "seller-farm-fresh", "amount": 750.00 },
    { "productId": "shipping", "amount": 99.00 }
  ],
  "metadata": {
    "communityId": "comm-blr-east",
    "subscriptionId": "sub-001"        // if subscription renewal
  }
}
```

**Response:**
```json
{
  "intentId": "pi-2026-abc123",
  "status": "CREATED",
  "amount": 849.00,
  "clientSecret": "pi_abc123_secret_xyz",  // for frontend SDK
  "expiresAt": "2026-08-05T13:00:00Z"
}
```

### Step 2: Payment Method Selection

Customer selects a payment method. Supported:

| Method | Type | Auto-charge? | Refund Support |
|--------|------|-------------|----------------|
| UPI | Real-time | Yes (UPI autopay mandate) | Yes |
| Credit/Debit Card | Gateway | Yes (tokenized) | Yes |
| Net Banking | Gateway | No (requires login each time) | Yes |
| Wallet (internal) | Prepaid | Yes | Credit back |
| Cash on Delivery | Offline | No | Manual |

### Step 3: Gateway Charge

paymentService calls the payment gateway (Razorpay/Stripe/PhonePe):

```
PaymentIntent CREATED
    → Customer confirms on frontend (UPI/card/netbanking)
    → Gateway webhook: payment.captured
    → paymentService: INTENT → CAPTURED
    → Publish: payment.events → ORDER_PAYMENT_CAPTURED
    → orderService: order status → PAYMENT_CONFIRMED → releases for allocation
```

### Step 4: Subscription Auto-Charge

For recurring subscriptions, paymentService auto-charges on the billing cycle:

```
CartCloseScheduler (cartService) closes community cart
    → CommunityOrder created in orderService (status: CREATED)
    → paymentService receives: subscription.billing.due event
    → Attempts charge on saved payment method (tokenized card / UPI autopay)
    → Success → order RELEASED for allocation
    → Failure → retry (3 attempts over 72h)
    → All retries exhausted → DUNNING event → customer notified, subscription HELD
```

---

## Partner Settlement Flow (Payout)

### Step 1: Settlement Trigger

When financeService marks an invoice as APPROVED (after delivery confirmation):

```
financeService: Invoice APPROVED (seller freight invoice, NET_30 terms)
    → Kafka: finance.invoice.events → INVOICE_APPROVED
    → paymentService: adds to settlement batch for this payer+payee pair
```

### Step 2: Settlement Batch

Settlements are batched by (payerPartyId, payeePartyId, paymentCycle):

```json
{
  "batchId": "stl-batch-2026-08-01",
  "payerPartyId": "org-havyaka",        // platform
  "payeePartyId": "carrier-swift",       // partner
  "invoices": ["INV-2026-00123", "INV-2026-00124", "INV-2026-00130"],
  "grossAmount": 45000.00,
  "deductions": {
    "platformCommission": 2250.00,       // 5% commission
    "tdsWithheld": 450.00,              // 1% TDS
    "gstOnCommission": 405.00           // 18% GST on commission
  },
  "netPayable": 41895.00,
  "paymentCycle": "NET_30",
  "dueDate": "2026-09-01",
  "status": "PENDING_APPROVAL"
}
```

### Step 3: Payout Execution

On the settlement due date (or when treasury approves):

```
Settlement batch APPROVED
    → paymentService initiates bank transfer (NEFT/RTGS/IMPS)
    → Bank confirms: transaction reference received
    → Settlement status: DISBURSED
    → Publish: settlement.events → SETTLEMENT_DISBURSED
    → financeService marks invoices as PAID
    → contractManager: settlement tracked against contract terms
```

### Step 4: Reconciliation

Daily reconciliation job:
- Match bank statement entries against settlement records
- Flag discrepancies (partial credits, bounces, delays)
- Auto-close matched settlements
- Escalate unmatched entries to ops

---

## Entities

### PaymentIntent (customer-facing)

| Field | Description |
|-------|-------------|
| intentId | UUID |
| orderId | Which order this payment is for |
| customerId | Who's paying |
| amount / currency | INR, USD |
| status | CREATED → PROCESSING → CAPTURED → FAILED → REFUNDED |
| method | UPI, CARD, NETBANKING, WALLET, COD |
| gatewayRef | External gateway transaction ID (Razorpay order_id, etc.) |
| gatewayResponse | Raw gateway callback payload (for dispute evidence) |
| capturedAt | When money was confirmed received |
| expiresAt | Intent auto-cancels if not completed |

### PaymentMethod (saved instruments)

| Field | Description |
|-------|-------------|
| methodId | UUID |
| customerId | Owner |
| type | CARD, UPI_AUTOPAY, WALLET |
| label | "Visa ending in 4242" |
| tokenRef | Gateway-issued token (never store raw card) |
| isDefault | Primary method for auto-charges |
| status | ACTIVE, EXPIRED, REVOKED |

### SettlementBatch (partner payouts)

| Field | Description |
|-------|-------------|
| batchId | UUID |
| payerPartyId / payeePartyId | Who pays whom |
| invoiceIds[] | Invoices included in this batch |
| grossAmount | Sum of invoice amounts |
| deductions | Commission, TDS, GST breakdown |
| netPayable | What actually gets transferred |
| paymentCycle | NET_7, NET_15, NET_30 (from contractManager terms) |
| dueDate | When payout should execute |
| status | PENDING → APPROVED → DISBURSED → RECONCILED |
| bankRef | Transaction reference from bank |

### Refund

| Field | Description |
|-------|-------------|
| refundId | UUID |
| intentId | Original payment being refunded |
| amount | Full or partial |
| reason | CUSTOMER_REQUEST, ORDER_CANCELLED, DAMAGED_GOODS, DUPLICATE |
| status | INITIATED → PROCESSED → FAILED |
| gatewayRefundId | Gateway's refund reference |

---

## API Endpoints

### Customer Payments

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/payments/intents` | Create payment intent (checkout) |
| GET | `/api/v1/payments/intents/{id}` | Get intent status |
| POST | `/api/v1/payments/intents/{id}/confirm` | Confirm with payment method |
| POST | `/api/v1/payments/intents/{id}/cancel` | Cancel unpaid intent |
| POST | `/api/v1/payments/webhook` | Gateway callback (Razorpay/Stripe) |
| GET | `/api/v1/payments/methods/{customerId}` | Saved payment methods |
| POST | `/api/v1/payments/methods` | Save new method (tokenize) |
| DELETE | `/api/v1/payments/methods/{methodId}` | Remove saved method |
| POST | `/api/v1/payments/refunds` | Initiate refund |
| GET | `/api/v1/payments/history/{customerId}` | Payment history |

### Partner Settlements

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/settlements/batches` | List settlement batches |
| GET | `/api/v1/settlements/batches/{id}` | Batch detail |
| POST | `/api/v1/settlements/batches/{id}/approve` | Approve for payout |
| POST | `/api/v1/settlements/batches/{id}/disburse` | Execute bank transfer |
| GET | `/api/v1/settlements/party/{partyId}` | Settlement history for a partner |
| POST | `/api/v1/settlements/reconcile` | Trigger reconciliation run |

---

## Kafka Events

### Published

| Topic | Event | Trigger | Consumers |
|-------|-------|---------|-----------|
| `payment.events` | ORDER_PAYMENT_CAPTURED | Customer pays | orderService (release order), data-platform |
| `payment.events` | PAYMENT_FAILED | Charge declined | orderService (hold order), notificationService |
| `payment.events` | SUBSCRIPTION_PAYMENT_FAILED | Auto-charge failed | cartService (hold subscription), notificationService |
| `payment.events` | REFUND_PROCESSED | Refund completed | orderService, financeService, notificationService |
| `settlement.events` | SETTLEMENT_DISBURSED | Payout sent | financeService (mark invoices PAID), contractManager |
| `settlement.events` | SETTLEMENT_FAILED | Bank transfer failed | ops dashboard, notificationService |

### Consumed

| Topic | Event | Action |
|-------|-------|--------|
| `community-order-events` | ORDER_CREATED | Create PaymentIntent for COD tracking |
| `subscription.billing.due` | BILLING_DUE | Auto-charge subscription |
| `finance.invoice.events` | INVOICE_APPROVED | Add to settlement batch |
| `order.lifecycle.events` | ORDER_CANCELLED | Auto-refund if already paid |

---

## How It Connects to Other Services

```
cartService (checkout)
    → paymentService: create intent, process payment
    → orderService: release order on CAPTURED

financeService (invoicing)
    → paymentService: settlement batches from approved invoices
    → paymentService: marks invoices PAID on disbursement

contractManager (payment terms)
    → paymentService: reads paymentCycle (NET_7/15/30) to determine settlement due dates
    → paymentService: commission % from contract terms drives deduction calculation

notificationService
    ← paymentService: payment confirmations, failures, refund notifications, payout receipts

data-platform
    ← paymentService: payment analytics (conversion rate, avg payment time, failure reasons)

moneyService (cost accounting)
    ← financeService: when paymentService disburses, financeService fires cost-events
```

---

## Security & Compliance

| Requirement | Implementation |
|-------------|---------------|
| PCI DSS | Never store raw card numbers. Use gateway tokenization. |
| Token vault | Payment method tokens stored encrypted, accessed via methodId only |
| Webhook verification | Validate gateway signature on every callback (Razorpay signature, Stripe signature) |
| Idempotency | Every intent/settlement has a unique idempotency key — retries don't double-charge |
| Audit trail | Every state transition logged with timestamp, actor, reason |
| Rate limiting | Gateway calls rate-limited to prevent abuse |
| Refund window | Configurable per payment method (card: 180 days, UPI: 30 days) |

---

## Settlement Deduction Logic

For every partner payout, deductions are calculated from contractManager terms:

```
grossAmount = sum(invoice amounts in batch)

platformCommission = grossAmount × contract.commissionPct
gstOnCommission = platformCommission × 18%  (GST on services)
tdsWithheld = grossAmount × contract.tdsRate  (typically 1-2%)

netPayable = grossAmount - platformCommission - gstOnCommission - tdsWithheld
```

These rates come directly from the contract's rate lines (ContractRateLine with chargeType = COMMISSION, PERCENTAGE basis).

---

## Subscription Billing & Dunning

```
Cycle 1: Auto-charge succeeds → order released → fulfillment
Cycle 2: Auto-charge fails (insufficient funds)
    → Retry 1: +24h (next day)
    → Retry 2: +48h
    → Retry 3: +72h
    → All failed: DUNNING
        → Subscription status: HELD (skips this cycle)
        → Customer notified: "Update payment method"
        → If no action in 7 days: subscription PAUSED
        → If no action in 30 days: subscription CANCELLED
```

---

## Configuration

```yaml
server:
  port: 8085

payment:
  gateway:
    provider: razorpay  # or stripe, phonepe
    api-key: ${RAZORPAY_KEY}
    api-secret: ${RAZORPAY_SECRET}
    webhook-secret: ${RAZORPAY_WEBHOOK_SECRET}
  retry:
    max-attempts: 3
    interval-hours: 24
  settlement:
    default-cycle: NET_30
    batch-schedule: "0 0 6 * * *"  # daily at 6 AM
    reconciliation-schedule: "0 0 8 * * *"  # daily at 8 AM
  refund:
    auto-refund-on-cancel: true
    max-refund-window-days: 180
```

## Port: 8085 | DB: PostgreSQL (paymentdb) | Gateway: Razorpay/Stripe
