# Event Ledger

Event Ledger is a Java 21 and Spring Boot system composed of two independently
runnable microservices:

- **Event Gateway** (`localhost:8080`) validates and stores incoming events,
  enforces idempotency, serves event queries, and calls Account Service.
- **Account Service** (`localhost:8081`) stores account transactions and computes
  balances as total credits minus total debits.

Each service owns a separate in-memory H2 database. They share no database or
in-process state and communicate synchronously over REST.

## Architecture

```text
Client
  |
  | HTTP
  v
Event Gateway :8080  -- HTTP + X-Trace-Id -->  Account Service :8081
  |                                             |
  v                                             v
Gateway H2                                    Account H2
```

Events are keyed by `eventId`. A duplicate submission returns the originally
stored event with `200 OK` and does not alter the account balance. Event queries
are ordered by `eventTimestamp`, so arrival order does not affect listings or
balance calculations.

## Prerequisites

Choose either:

- Docker Desktop with Docker Compose v2, or
- JDK 21+ for local execution

Running the end-to-end script also requires `curl` and `python3`.

## Start With Docker

```bash
docker compose up --build
```

Compose waits for Account Service to become healthy before starting Gateway.
Stop both services with:

```bash
docker compose down
```

## Start Locally

Build both services:

```bash
./mvnw clean package
```

Start Account Service:

```bash
java -jar account-service/target/account-service-0.0.1-SNAPSHOT.jar
```

In another terminal, start Event Gateway:

```bash
ACCOUNT_SERVICE_BASE_URL=http://localhost:8081 \
  java -jar event-gateway/target/event-gateway-0.0.1-SNAPSHOT.jar
```

## Public Gateway API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/events` | Validate, apply, and store an event |
| `GET` | `/events/{eventId}` | Get one locally stored event |
| `GET` | `/events?account={accountId}` | List account events chronologically |
| `GET` | `/accounts/{accountId}/balance` | Proxy the Account Service balance |
| `GET` | `/health` | Service and database diagnostics |
| `GET` | `/actuator/health` | Spring Boot health information |
| `GET` | `/actuator/metrics` | Available application metrics |

Example:

```bash
curl -i -X POST http://localhost:8080/events \
  -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: trace-client-001' \
  --data '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 150.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T14:02:11Z",
    "metadata": {
      "source": "mainframe-batch",
      "batchId": "B-9042"
    }
  }'
```

## Internal Account API

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/accounts/{accountId}/transactions` | Apply an idempotent transaction |
| `GET` | `/accounts/{accountId}/balance` | Get the current balance |
| `GET` | `/accounts/{accountId}` | Get account details and recent transactions |
| `GET` | `/health` | Service and database diagnostics |

## Resiliency

Gateway uses timeout plus retry with exponential backoff for Account Service
calls. It retries transport failures and `5xx` responses, but not `4xx`
rejections. Retrying transaction submissions is safe because Account Service
also enforces idempotency by `eventId`.

Default settings:

| Environment variable | Default |
|---|---|
| `ACCOUNT_SERVICE_CONNECT_TIMEOUT` | `500ms` |
| `ACCOUNT_SERVICE_READ_TIMEOUT` | `1s` |
| `ACCOUNT_SERVICE_MAX_ATTEMPTS` | `3` |
| `ACCOUNT_SERVICE_INITIAL_BACKOFF` | `100ms` |

After all attempts fail, Gateway returns `503 Service Unavailable`. Gateway-local
event reads continue working while Account Service is down; balance queries
return a clear `503`.

## Tracing And Observability

Gateway accepts an `X-Trace-Id` header or creates a 32-character trace ID. It
returns that ID to the client and forwards it to Account Service. Both services
write JSON logs containing timestamp, level, service name, and trace ID.

Custom counters are exposed through:

```text
/actuator/metrics/event_ledger_gateway_submissions
/actuator/metrics/event_ledger_account_transactions
```

## Tests

Run all automated tests:

```bash
./mvnw test
```

Run the real-container end-to-end scenario:

```bash
./scripts/e2e-test.sh
```

The end-to-end test builds and starts both containers, verifies trace
propagation, idempotency, out-of-order listing, balance computation, and then
stops Account Service to verify graceful degradation. It removes its containers
when complete and uses the isolated Compose project name `event-ledger-e2e`.
