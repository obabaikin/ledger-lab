CREATE TABLE accounts (
    run_id                uuid    NOT NULL,
    account_no            integer NOT NULL,
    opening_balance_cents bigint  NOT NULL CHECK (opening_balance_cents >= 0),
    balance_cents         bigint  NOT NULL CHECK (balance_cents >= 0),
    status                text    NOT NULL CHECK (status IN ('ACTIVE', 'BLOCKED')),
    daily_limit_cents     bigint  NOT NULL CHECK (daily_limit_cents >= 0),
    daily_spent_cents     bigint  NOT NULL DEFAULT 0 CHECK (daily_spent_cents >= 0),
    PRIMARY KEY (run_id, account_no)
);

CREATE TABLE transfers (
    run_id        uuid        NOT NULL,
    transfer_id   uuid        NOT NULL,
    from_account  integer     NOT NULL,
    to_account    integer     NOT NULL,
    amount_cents  bigint      NOT NULL,
    outcome       text        NOT NULL CHECK (outcome IN ('POSTED', 'REJECTED')),
    reject_reason text,
    processed_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, transfer_id),
    CHECK ((outcome = 'POSTED') = (reject_reason IS NULL)),
    -- a rejected transfer records the requested amount as-is, even when that amount was the reason
    CHECK (outcome = 'REJECTED' OR amount_cents > 0)
);

-- Double-entry journal: every posted transfer produces exactly two rows that sum to zero.
CREATE TABLE journal_entries (
    id           bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id       uuid    NOT NULL,
    transfer_id  uuid    NOT NULL,
    account_no   integer NOT NULL,
    amount_cents bigint  NOT NULL CHECK (amount_cents <> 0),
    FOREIGN KEY (run_id, transfer_id) REFERENCES transfers (run_id, transfer_id) ON DELETE CASCADE,
    FOREIGN KEY (run_id, account_no) REFERENCES accounts (run_id, account_no) ON DELETE CASCADE
);

CREATE INDEX journal_entries_run_account_idx ON journal_entries (run_id, account_no);
CREATE INDEX journal_entries_run_transfer_idx ON journal_entries (run_id, transfer_id);
CREATE INDEX transfers_run_outcome_idx ON transfers (run_id, outcome);
