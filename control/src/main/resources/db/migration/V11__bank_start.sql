-- When the bank started, as it happened: the periods know only the hour it fell in.
CREATE TABLE bank_start (
    only_row   boolean     PRIMARY KEY DEFAULT true CHECK (only_row),
    started_at timestamptz NOT NULL
);

-- A bank that ran before this table knows only the start of its first period.
INSERT INTO bank_start (started_at) SELECT min(period_start) FROM periods HAVING count(*) > 0;
