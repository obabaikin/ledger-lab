-- A posted transfer's credit waits here, one row per receiver and batch, until the applier adds it to the balance and
-- deletes the row; the journal already holds it, so balance_cents + these rows = opening + journal (ADR 0018).
-- Only credits not yet applied stay, so the table stays small and needs no index beyond its key.
CREATE TABLE deferred_credits (
    id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id       uuid        NOT NULL,
    account_no   integer     NOT NULL,
    amount_cents bigint      NOT NULL CHECK (amount_cents > 0),
    created_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (run_id, account_no) REFERENCES accounts (run_id, account_no) ON DELETE CASCADE
);
