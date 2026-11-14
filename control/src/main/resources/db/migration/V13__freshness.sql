ALTER TABLE availability_minutes
    ADD COLUMN transfers bigint,
    ADD COLUMN slow      bigint,
    ADD COLUMN restarted boolean;

ALTER TABLE availability_hours
    ADD COLUMN outside_minutes integer NOT NULL DEFAULT 0,
    ADD COLUMN fresh_measured  integer NOT NULL DEFAULT 0,
    ADD COLUMN fresh_bad       integer NOT NULL DEFAULT 0,
    ADD COLUMN fresh_planned   integer NOT NULL DEFAULT 0,
    ADD COLUMN fresh_outside   integer NOT NULL DEFAULT 0;

CREATE INDEX availability_minutes_restarted_idx ON availability_minutes (minute) WHERE restarted;
