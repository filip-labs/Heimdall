CREATE TABLE event_outbox (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL,
    kafka_topic VARCHAR(200) NOT NULL,
    kafka_key VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error VARCHAR(1000)
);

CREATE INDEX idx_event_outbox_pending
    ON event_outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL;
