CREATE TABLE IF NOT EXISTS account_transactions (
    event_id VARCHAR(100) PRIMARY KEY,
    account_id VARCHAR(100) NOT NULL,
    transaction_type VARCHAR(10) NOT NULL,
    amount DECIMAL(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    metadata_json CLOB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_account_transactions_account_timestamp
    ON account_transactions (account_id, event_timestamp, event_id);
