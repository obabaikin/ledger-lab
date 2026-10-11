-- Counted when the period closes; a closed period's set of transfers is frozen, so counting now gives the same number.
ALTER TABLE run_periods ADD COLUMN entry_count bigint;
UPDATE run_periods p SET entry_count = (SELECT count(*) FROM transfers t WHERE t.run_id = p.run_id);
ALTER TABLE run_periods ALTER COLUMN entry_count SET NOT NULL;
