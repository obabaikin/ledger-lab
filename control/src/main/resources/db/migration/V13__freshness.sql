ALTER TABLE availability_minutes
    ADD COLUMN transfers bigint,
    ADD COLUMN slow      bigint;

ALTER TABLE availability_hours
    ADD COLUMN outside_minutes integer NOT NULL DEFAULT 0,
    ADD COLUMN fresh_measured  integer NOT NULL DEFAULT 0,
    ADD COLUMN fresh_bad       integer NOT NULL DEFAULT 0,
    ADD COLUMN fresh_planned   integer NOT NULL DEFAULT 0,
    ADD COLUMN fresh_outside   integer NOT NULL DEFAULT 0;

CREATE TABLE service_starts (
    instance   text        NOT NULL,
    started_at timestamptz NOT NULL,
    PRIMARY KEY (instance, started_at)
);

CREATE INDEX service_starts_started_at_idx ON service_starts (started_at);
