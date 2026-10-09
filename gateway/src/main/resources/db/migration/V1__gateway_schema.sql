-- One row per transfer accepted at the edge. The row is also the outbox:
-- published_at IS NULL means the command has not reached Kafka yet.
CREATE TABLE transfers (
    transfer_id   uuid        PRIMARY KEY,
    run_id        uuid        NOT NULL,
    from_account  integer     NOT NULL,
    to_account    integer     NOT NULL,
    amount_cents  bigint      NOT NULL,
    status        text        NOT NULL CHECK (status IN ('ACCEPTED', 'PUBLISHED', 'POSTED', 'REJECTED')),
    reject_reason text,
    accepted_at   timestamptz NOT NULL DEFAULT now(),
    published_at  timestamptz,
    resulted_at   timestamptz,
    CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL))
);

CREATE INDEX transfers_unpublished_idx ON transfers (accepted_at) WHERE published_at IS NULL;
CREATE INDEX transfers_run_idx ON transfers (run_id);
