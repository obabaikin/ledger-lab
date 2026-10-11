ALTER TABLE runs
    ADD COLUMN started_at        timestamptz,
    ADD COLUMN stop_requested_at timestamptz,
    ADD COLUMN client_lost       boolean NOT NULL DEFAULT false,
    ADD COLUMN drain_started_at  timestamptz,
    ADD COLUMN closed_at         timestamptz,
    ADD COLUMN drain_applied     bigint,
    ADD COLUMN drain_progress_at timestamptz,
    ADD COLUMN result            text CHECK (result IN ('BALANCED', 'BREAKS', 'INCOMPLETE')),
    ADD COLUMN failure           text,
    ADD COLUMN report            jsonb,
    ADD COLUMN purged_at         timestamptz;
