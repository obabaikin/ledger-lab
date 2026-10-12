ALTER TABLE runs ADD COLUMN start_sent_at timestamptz;
ALTER TABLE runs RENAME COLUMN drain_applied TO drain_progress;
