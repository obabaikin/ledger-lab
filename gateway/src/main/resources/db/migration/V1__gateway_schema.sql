-- One bank for all time (ADR 0021). A key is taken here first: a repeated submission finds it and gets the first
-- answer. Not partitioned, so a key is found by the id alone; rows older than the idempotency window are deleted in
-- batches (ADR 0022).
CREATE TABLE transfer_keys (
    transfer_id uuid        PRIMARY KEY,
    accepted_at timestamptz NOT NULL
);

CREATE INDEX transfer_keys_accepted_idx ON transfer_keys (accepted_at);

-- One row per transfer accepted at the edge, split by the hour it was accepted (ADR 0022): a period's statement is
-- read from its own partition, which is dropped whole. The row is also the outbox: published_at IS NULL means the
-- command has not reached Kafka yet.
CREATE TABLE transfers (
    accepted_at   timestamptz NOT NULL,
    transfer_id   uuid        NOT NULL,
    from_account  integer     NOT NULL,
    to_account    integer     NOT NULL,
    amount_cents  bigint      NOT NULL,
    status        text        NOT NULL CHECK (status IN ('ACCEPTED', 'PUBLISHED', 'POSTED', 'REJECTED')),
    reject_reason text,
    published_at  timestamptz,
    resulted_at   timestamptz,
    PRIMARY KEY (accepted_at, transfer_id),
    CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL))
) PARTITION BY RANGE (accepted_at);

-- Takes what no partition covers, so a missing partition never stops the bank.
CREATE TABLE transfers_default PARTITION OF transfers DEFAULT;

CREATE INDEX transfers_unpublished_idx ON transfers (accepted_at) WHERE published_at IS NULL;

-- A row means the period is closed: every transfer accepted in it had committed before closed_at.
CREATE TABLE periods (
    period_start timestamptz PRIMARY KEY,
    closed_at    timestamptz NOT NULL,
    entry_count  bigint      NOT NULL
);
