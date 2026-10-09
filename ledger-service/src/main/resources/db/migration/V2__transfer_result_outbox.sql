-- The transfer row doubles as the outbox for the result event: NULL means "not yet published".
ALTER TABLE transfers ADD COLUMN result_published_at timestamptz;

-- Transfers processed before this migration must not be replayed as fresh results.
UPDATE transfers SET result_published_at = now() WHERE result_published_at IS NULL;

CREATE INDEX transfers_unpublished_result_idx
    ON transfers (processed_at)
    WHERE result_published_at IS NULL;
