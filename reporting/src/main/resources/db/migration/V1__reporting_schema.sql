-- One bank for all time (ADR 0021). One row per transfer, filled from two topics in any order: the command sets the
-- amount, the result sets the outcome and processed_at; both carry accepted_at. The row is what makes a redelivered
-- event count once. Split by the period the transfer was accepted in and dropped whole (ADR 0022).
CREATE TABLE transfer_stats (
    accepted_at   timestamptz NOT NULL,
    transfer_id   uuid        NOT NULL,
    amount_cents  bigint,
    outcome       text,
    reject_reason text,
    processed_at  timestamptz,
    PRIMARY KEY (accepted_at, transfer_id)
) PARTITION BY RANGE (accepted_at);

-- Takes what no partition covers, so a missing partition never stops the projection.
CREATE TABLE transfer_stats_default PARTITION OF transfer_stats DEFAULT;

-- The numbers of the transfers accepted in a second, added to as their events arrive: a period's numbers are a sum
-- over its seconds, not a count over its transfers. An outcome is counted in the second its transfer was accepted in,
-- so a drained period has accepted = posted + rejected.
CREATE TABLE second_stats (
    second              timestamptz PRIMARY KEY,
    accepted            bigint NOT NULL,
    commands_seen       bigint NOT NULL,
    posted              bigint NOT NULL,
    rejected            bigint NOT NULL,
    posted_amount_cents bigint NOT NULL
);

CREATE TABLE second_rejections (
    second        timestamptz NOT NULL,
    reject_reason text        NOT NULL,
    rejected      bigint      NOT NULL,
    PRIMARY KEY (second, reject_reason)
);
