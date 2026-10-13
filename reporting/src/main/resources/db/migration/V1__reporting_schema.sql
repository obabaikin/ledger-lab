-- One row per transfer, filled from two topics in any order: the command sets the amount and accepted_at,
-- the result sets the outcome and processed_at.
CREATE TABLE transfer_stats (
    transfer_id   uuid        PRIMARY KEY,
    run_id        uuid        NOT NULL,
    amount_cents  bigint,
    accepted_at   timestamptz,
    outcome       text,
    reject_reason text,
    processed_at  timestamptz
);

CREATE INDEX transfer_stats_run_idx ON transfer_stats (run_id);
