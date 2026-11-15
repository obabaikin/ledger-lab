-- The journal of the bank's clients (ADR 0021): what they meant to send and what they learned, written by inserts only.
-- An intent is committed before its request leaves, so a transfer the bank has is always one the journal knows.
-- Split by the hour the client sent it (ADR 0022); a partition is dropped whole.
CREATE TABLE intents (
    sent_at      timestamptz NOT NULL,
    transfer_id  uuid        NOT NULL,
    from_account integer     NOT NULL,
    to_account   integer     NOT NULL,
    amount_cents bigint      NOT NULL,
    PRIMARY KEY (sent_at, transfer_id)
) PARTITION BY RANGE (sent_at);

-- Takes what no partition covers, so a missing partition never stops the clients.
CREATE TABLE intents_default PARTITION OF intents DEFAULT;

-- What the client learned about an intent, once. An intent without a row here is unknown: the client gave up, or the
-- generator stopped before the answer. The reconciliation of the next period asks the bank and writes the answer here.
CREATE TABLE outcomes (
    sent_at       timestamptz NOT NULL,
    transfer_id   uuid        NOT NULL,
    outcome       text        NOT NULL CHECK (outcome IN ('ACKNOWLEDGED', 'FAILED', 'UNKNOWN_ACCEPTED', 'UNKNOWN_NOT_ACCEPTED')),
    bank_status   text,
    reject_reason text,
    accepted_at   timestamptz,
    PRIMARY KEY (sent_at, transfer_id)
) PARTITION BY RANGE (sent_at);

CREATE TABLE outcomes_default PARTITION OF outcomes DEFAULT;
