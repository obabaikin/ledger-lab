CREATE TABLE chaos_events (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at        timestamptz NOT NULL,
    instances text[]      NOT NULL
);

CREATE INDEX chaos_events_at ON chaos_events (at);
