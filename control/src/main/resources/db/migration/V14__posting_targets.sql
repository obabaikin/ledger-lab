-- Counted from this version on: the minutes before it have only the old 2 s bound and are not read again.
UPDATE availability_minutes SET transfers = NULL;

ALTER TABLE availability_minutes
    DROP COLUMN slow,
    ADD COLUMN within_typical bigint,
    ADD COLUMN within_tail    bigint,
    ADD COLUMN base_tps       integer;

ALTER TABLE availability_hours
    DROP COLUMN fresh_measured,
    DROP COLUMN fresh_bad,
    DROP COLUMN fresh_planned,
    DROP COLUMN fresh_outside,
    ADD COLUMN posted_judged         bigint NOT NULL DEFAULT 0,
    ADD COLUMN posted_within_typical bigint NOT NULL DEFAULT 0,
    ADD COLUMN posted_within_tail    bigint NOT NULL DEFAULT 0,
    ADD COLUMN posted_planned        bigint NOT NULL DEFAULT 0,
    ADD COLUMN posted_outside        bigint NOT NULL DEFAULT 0;
