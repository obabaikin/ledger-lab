-- One bank for all time (ADR 0021). Account 0 is the pool, 1 and up belong to clients.
-- Balances are rewritten in place on every transfer; the free space keeps the new row version on the same page.
CREATE TABLE accounts (
    account_no            integer PRIMARY KEY,
    opening_balance_cents bigint  NOT NULL CHECK (opening_balance_cents >= 0),
    balance_cents         bigint  NOT NULL CHECK (balance_cents >= 0),
    status                text    NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED'))
) WITH (fillfactor = 70);

-- Split by the hour the gateway accepted the transfer (ADR 0022): a period is reconciled from its own partition and
-- dropped whole. The key carries accepted_at, which a redelivered command repeats, so it stays the idempotency guard.
CREATE TABLE transfers (
    accepted_at         timestamptz NOT NULL,
    transfer_id         uuid        NOT NULL,
    kind                text        NOT NULL CHECK (kind IN ('CLIENT', 'POOL')),
    from_account        integer     NOT NULL,
    to_account          integer     NOT NULL,
    amount_cents        bigint      NOT NULL,
    outcome             text        NOT NULL CHECK (outcome IN ('POSTED', 'REJECTED')),
    reject_reason       text,
    processed_at        timestamptz NOT NULL DEFAULT now(),
    -- The row doubles as the outbox for the result event: NULL means "not yet published".
    result_published_at timestamptz,
    PRIMARY KEY (accepted_at, transfer_id),
    CHECK ((outcome = 'POSTED') = (reject_reason IS NULL)),
    -- a rejected transfer records the requested amount as-is, even when that amount was the reason
    CHECK (outcome = 'REJECTED' OR amount_cents > 0)
) PARTITION BY RANGE (accepted_at);

-- Takes what no partition covers, so a missing partition never stops the bank.
CREATE TABLE transfers_default PARTITION OF transfers DEFAULT;

CREATE INDEX transfers_unpublished_result_idx
    ON transfers (processed_at)
    WHERE result_published_at IS NULL;

-- Double-entry journal: every posted transfer produces exactly two rows that sum to zero.
-- No key to transfers: a foreign key to a partitioned table would stand in the way of dropping a partition.
-- No index: the journal is read a whole partition at a time.
CREATE TABLE journal_entries (
    accepted_at  timestamptz NOT NULL,
    transfer_id  uuid        NOT NULL,
    account_no   integer     NOT NULL REFERENCES accounts (account_no),
    amount_cents bigint      NOT NULL CHECK (amount_cents <> 0)
) PARTITION BY RANGE (accepted_at);

CREATE TABLE journal_entries_default PARTITION OF journal_entries DEFAULT;

-- A posted transfer's credit waits here, one row per receiver and batch, until the applier adds it to the balance and
-- deletes the row; the journal already holds it, so balance_cents + these rows = opening + journal (ADR 0018).
-- Only credits not yet applied stay, so the table stays small and needs no index beyond its key.
CREATE TABLE deferred_credits (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_no   integer     NOT NULL REFERENCES accounts (account_no),
    amount_cents bigint      NOT NULL CHECK (amount_cents > 0),
    created_at   timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- Every account's balance at the end of a period; two neighbours and the period's journal check each account.
CREATE TABLE account_snapshots (
    period_start  timestamptz NOT NULL,
    account_no    integer     NOT NULL,
    balance_cents bigint      NOT NULL,
    PRIMARY KEY (period_start, account_no)
);

CREATE TABLE period_checks (
    period_start timestamptz PRIMARY KEY,
    finished_at  timestamptz NOT NULL DEFAULT now(),
    report       jsonb       NOT NULL
);

-- One row: where the pool's round over the accounts stands and when it last took the hot accounts' surplus.
CREATE TABLE pool_state (
    id                boolean     PRIMARY KEY DEFAULT true CHECK (id),
    next_account_no   integer     NOT NULL,
    round_advanced_at timestamptz NOT NULL,
    hot_swept_at      timestamptz NOT NULL
);

INSERT INTO pool_state (next_account_no, round_advanced_at, hot_swept_at) VALUES (1, now(), now());
