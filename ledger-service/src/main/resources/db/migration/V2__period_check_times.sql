-- The check's start and end, to tell how much of a period's closing the check takes. Checks made before keep their one time as both.
ALTER TABLE period_checks RENAME COLUMN finished_at TO made_at;
ALTER TABLE period_checks ALTER COLUMN made_at DROP DEFAULT;
ALTER TABLE period_checks ADD COLUMN started_at timestamptz;
UPDATE period_checks SET started_at = made_at;
ALTER TABLE period_checks ALTER COLUMN started_at SET NOT NULL;
