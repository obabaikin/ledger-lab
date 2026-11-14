-- Control's next action on a period, taken by one instance only, and the bank's counts it read once for the verdict.
ALTER TABLE periods
    ADD COLUMN next_action_at timestamptz,
    ADD COLUMN gateway_counts jsonb,
    ADD COLUMN ledger_counts  jsonb;
