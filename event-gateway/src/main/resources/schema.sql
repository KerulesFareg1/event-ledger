CREATE TABLE IF NOT EXISTS ledger_events (
    event_id VARCHAR(100) PRIMARY KEY,
    account_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(10) NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    metadata_json CLOB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ledger_events_account_timestamp
    ON ledger_events (account_id, event_timestamp, event_id);
