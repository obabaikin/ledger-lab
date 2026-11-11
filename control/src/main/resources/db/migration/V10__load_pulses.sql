CREATE TABLE load_pulses (
    id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at      timestamptz NOT NULL,
    tps     integer     NOT NULL,
    seconds integer     NOT NULL,
    until   timestamptz NOT NULL
);

CREATE INDEX load_pulses_until_idx ON load_pulses (until);
