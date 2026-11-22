-- The bank's answers to the clients by minute. Every instance adds its own counts, so a minute two pods shared during a
-- deploy holds the answers of both.
CREATE TABLE response_minutes (
    minute timestamptz PRIMARY KEY,
    total  bigint      NOT NULL,
    errors bigint      NOT NULL,
    busy   bigint      NOT NULL
);
