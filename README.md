# TradeSync Engine

A **Concurrent Trade Reconciliation Engine** in Java demonstrating production-grade financial systems engineering.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      TradeSync Engine                           │
│                                                                 │
│  ┌──────────┐     ┌─────────────────────┐    ┌──────────────┐  │
│  │ Producer │──┐  │  LinkedBlockingQueue │    │  REST API    │  │
│  │ Thread 1 │  │  │   (capacity 50,000) │    │  (Javalin)   │  │
│  └──────────┘  │  └──────────┬──────────┘    │  :7070       │  │
│  ┌──────────┐  ├──►          │               └──────────────┘  │
│  │ Producer │  │  ┌──────────▼──────────┐                      │
│  │ Thread 2 │  │  │  Consumer Threads   │    ┌──────────────┐  │
│  └──────────┘  │  │  ┌──────────────┐  │    │Reconciliation│  │
│  ┌──────────┐  │  │  │ FSM Pipeline │  │    │  Scheduler   │  │
│  │ Producer │──┘  │  │ RECEIVED     │  │    │  (30s cycle) │  │
│  │ Thread N │     │  │ VALIDATED    │  │    └──────────────┘  │
│  └──────────┘     │  │ MATCHED      │  │                      │
│                   │  │ SETTLED      │  │    ┌──────────────┐  │
│                   │  └──────┬───────┘  │    │  PostgreSQL  │  │
│                   └─────────┼──────────┘    │  (HikariCP)  │  │
│                             │               └──────────────┘  │
│                    ┌────────▼────────┐                        │
│                    │   Order Book    │  ←── ReentrantRWLock   │
│                    │  (RW-locked)    │                        │
│                    └────────┬────────┘                        │
│                             │                                 │
│                    ┌────────▼────────┐                        │
│                    │  PnL Calculator │  (FIFO lots)           │
│                    └─────────────────┘                        │
└─────────────────────────────────────────────────────────────────┘
```

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 21 |
| Build | Maven |
| REST API | Javalin 6.x |
| Database | PostgreSQL 16 |
| JDBC Pool | HikariCP |
| JSON | Jackson |
| Logging | SLF4J + Logback |
| Tests | JUnit 5 + Mockito |
| Container | Docker + Compose |

## Quick Start

### Option 1 — Docker (recommended)

```bash
# Build and start everything
docker-compose up --build

# Verify
curl -H "X-API-Key: tradesync-secret-key-2024" http://localhost:7070/health
```

### Option 2 — Local (requires PostgreSQL running)

```bash
# 1. Start PostgreSQL
createdb tradesync
psql -c "CREATE USER tradesync WITH PASSWORD 'tradesync_secret';"
psql -c "GRANT ALL ON DATABASE tradesync TO tradesync;"

# 2. Build
mvn clean package -DskipTests

# 3. Run
java -jar target/tradesync-engine-1.0.0.jar
```

## API Reference

All endpoints require `X-API-Key: tradesync-secret-key-2024` (except `/health`).

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | DB connectivity check (no auth) |
| `GET` | `/trades?page=0&size=20` | Paginated trade list |
| `GET` | `/trades/{id}` | Single trade + full audit trail |
| `POST` | `/trades` | Ingest a new trade |
| `GET` | `/reconciliation/latest` | Latest reconciliation report |
| `GET` | `/pnl/{symbol}` | Realized + unrealized + total P&L |
| `GET` | `/metrics` | Queue depth, throughput, avg latency |

### Example Requests

```bash
KEY="X-API-Key: tradesync-secret-key-2024"

# Health
curl http://localhost:7070/health

# List trades
curl -H "$KEY" "http://localhost:7070/trades?page=0&size=10"

# Ingest a trade
curl -H "$KEY" -H "Content-Type: application/json" -X POST \
  -d '{"symbol":"AAPL","quantity":500,"price":175.50,"side":"BUY"}' \
  http://localhost:7070/trades

# P&L for AAPL
curl -H "$KEY" http://localhost:7070/pnl/AAPL

# Reconciliation report
curl -H "$KEY" http://localhost:7070/reconciliation/latest

# Metrics
curl -H "$KEY" http://localhost:7070/metrics
```

## Running Tests

```bash
mvn test
```

Tests cover:
- **OrderStateMachineTest** — all valid transitions, all illegal transitions, terminal state guards, audit recording verification
- **ReconciliationJobTest** — MATCHED, BREAK, MISSING scenarios, mixed reports, diff sign correctness

## Load Benchmark

```bash
# 20 threads × 500 trades = 10,000 total
java -jar target/tradesync-engine-1.0.0.jar --benchmark --threads=20 --trades=500

# Higher load
java -jar target/tradesync-engine-1.0.0.jar --benchmark --threads=50 --trades=1000
```

Sample output:
```
╔══════════════════════════════════════╗
║       TradeSync Load Test Results   ║
╠══════════════════════════════════════╣
║  Threads:       20                  ║
║  Total trades:  10000               ║
║  Elapsed:       0.847s              ║
║  Throughput:    11806 t/s           ║
║  Enqueue P50:   12µs                ║
║  Enqueue P99:   234µs               ║
║  Enqueue P99.9: 891µs               ║
╚══════════════════════════════════════╝
```

## Database Schema

```sql
trades(id UUID, symbol, quantity, price, side, state, created_at)
trade_audit(id, trade_id, from_state, to_state, ts, reason)
ledger_positions(id, book_name, symbol, quantity, avg_price)
reconciliation_runs(id, run_at, matched, breaks, missing, report_json)
```

## Key Design Decisions

- **`ReentrantReadWriteLock`** on `OrderBook` — reads never block each other; only writes take exclusive lock
- **`LinkedBlockingQueue`** provides natural back-pressure — producers block instead of OOMing the JVM
- **FIFO P&L** using `ArrayDeque<Lot>` per symbol — O(1) amortized, no global locking needed
- **`LongAdder`** in `MetricsRegistry` — outperforms `AtomicLong` under high contention
- **Raw JDBC** with `PreparedStatement` — demonstrates SQL injection awareness, no ORM magic
- **`ScheduledExecutorService`** for reconciliation — guaranteed single-threaded, no races on the report reference
- **`AtomicReference<ReconciliationReport>`** — zero-copy lock-free reads from the REST layer
