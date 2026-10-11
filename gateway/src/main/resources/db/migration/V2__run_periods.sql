-- A row means the run's period is closed: every transfer of the run committed before closed_at,
-- and no new one commits after it (ADR 0009).
CREATE TABLE run_periods (
    run_id    uuid        PRIMARY KEY,
    closed_at timestamptz NOT NULL
);

-- Statement order; the run_id prefix also serves the summary and deleting a run.
DROP INDEX transfers_run_idx;
CREATE INDEX transfers_run_order_idx ON transfers (run_id, accepted_at, transfer_id);
