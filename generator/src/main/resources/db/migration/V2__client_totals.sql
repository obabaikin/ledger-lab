-- What the clients sent and learned since the bank started, kept apart from the journal, whose partitions are dropped.
-- The writer of the journal adds one row of increments per batch, in the batch's transaction; a job folds them into the
-- one row of totals now and then. The writer never updates a row: a row it updated 50 times a second would keep a dead
-- version for each while any long transaction of any service in this database is open.
CREATE TABLE client_totals (
    only_row             boolean     PRIMARY KEY DEFAULT true CHECK (only_row),
    started_at           timestamptz NOT NULL,
    sent                 bigint      NOT NULL,
    acknowledged         bigint      NOT NULL,
    failed               bigint      NOT NULL,
    unknown_accepted     bigint      NOT NULL,
    unknown_not_accepted bigint      NOT NULL
);

CREATE TABLE client_totals_delta (
    sent                 bigint NOT NULL,
    acknowledged         bigint NOT NULL,
    failed               bigint NOT NULL,
    unknown_accepted     bigint NOT NULL,
    unknown_not_accepted bigint NOT NULL
);

-- The journal still holds what it has not dropped; what it dropped before is lost to the totals. Only outcomes whose
-- intent is still there count, or unanswered (sent less every outcome) would start below zero.
INSERT INTO client_totals (started_at, sent, acknowledged, failed, unknown_accepted, unknown_not_accepted)
SELECT coalesce((SELECT min(sent_at) FROM intents), now()),
       (SELECT count(*) FROM intents),
       count(*) FILTER (WHERE o.outcome = 'ACKNOWLEDGED'),
       count(*) FILTER (WHERE o.outcome = 'FAILED'),
       count(*) FILTER (WHERE o.outcome = 'UNKNOWN_ACCEPTED'),
       count(*) FILTER (WHERE o.outcome = 'UNKNOWN_NOT_ACCEPTED')
FROM outcomes o JOIN intents i ON i.sent_at = o.sent_at AND i.transfer_id = o.transfer_id;
