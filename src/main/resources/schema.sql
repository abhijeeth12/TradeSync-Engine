-- TradeSync Engine Schema
-- Executed on first startup by DatabaseManager

CREATE TABLE IF NOT EXISTS trades (
    id          UUID PRIMARY KEY,
    symbol      VARCHAR(20)    NOT NULL,
    quantity    BIGINT         NOT NULL,
    price       NUMERIC(18,6)  NOT NULL,
    side        VARCHAR(4)     NOT NULL,
    state       VARCHAR(20)    NOT NULL DEFAULT 'RECEIVED',
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_trades_symbol ON trades(symbol);
CREATE INDEX IF NOT EXISTS idx_trades_state  ON trades(state);

CREATE TABLE IF NOT EXISTS trade_audit (
    id          BIGSERIAL PRIMARY KEY,
    trade_id    UUID           NOT NULL REFERENCES trades(id) ON DELETE CASCADE,
    from_state  VARCHAR(20),
    to_state    VARCHAR(20)    NOT NULL,
    ts          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    reason      TEXT
);

CREATE INDEX IF NOT EXISTS idx_audit_trade_id ON trade_audit(trade_id);

CREATE TABLE IF NOT EXISTS ledger_positions (
    id          BIGSERIAL PRIMARY KEY,
    book_name   VARCHAR(50)    NOT NULL,
    symbol      VARCHAR(20)    NOT NULL,
    quantity    BIGINT         NOT NULL,
    avg_price   NUMERIC(18,6)  NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    UNIQUE (book_name, symbol)
);

CREATE TABLE IF NOT EXISTS reconciliation_runs (
    id          BIGSERIAL PRIMARY KEY,
    run_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    matched     INT            NOT NULL DEFAULT 0,
    breaks      INT            NOT NULL DEFAULT 0,
    missing     INT            NOT NULL DEFAULT 0,
    report_json TEXT
);
