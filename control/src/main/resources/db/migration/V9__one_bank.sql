DROP TABLE runs;

ALTER TABLE invite_tokens DROP COLUMN quota;

CREATE TABLE periods (
    period_start        timestamptz PRIMARY KEY,
    period_end          timestamptz NOT NULL CHECK (period_end > period_start),
    state               text        NOT NULL CHECK (state IN ('OPEN', 'CLOSING', 'DRAINING', 'RECONCILED', 'FAILED')),
    closing_started_at  timestamptz,
    closed_at           timestamptz,
    entry_count         bigint,
    drain_progress      bigint,
    drain_progress_at   timestamptz,
    books_final_at      timestamptz,
    check_requested_at  timestamptz,
    client_requested_at timestamptz,
    verdict             text CHECK (verdict IN ('BALANCED', 'MISMATCH')),
    failure             text CHECK (failure IN ('PERIOD_NOT_CLOSED', 'PERIOD_UNKNOWN_TO_BANK', 'BANK_UNAVAILABLE', 'DRAIN_STALLED', 'DRAIN_TIMED_OUT', 'MORE_RESULTS_THAN_ACCEPTED',
                                                'LEDGER_CHECK_UNAVAILABLE',
                                                'BOOKS_UNAVAILABLE', 'CLIENT_RECONCILIATION_UNAVAILABLE', 'DETAILS_EXPIRED')),
    report              jsonb,
    finished_at         timestamptz,
    accepted            bigint,
    posted              bigint,
    rejected            bigint,
    posted_amount_cents bigint,
    rejections          jsonb,
    CHECK ((state = 'RECONCILED') = (verdict IS NOT NULL)),
    CHECK ((state = 'FAILED') = (failure IS NOT NULL))
);

CREATE INDEX periods_unfinished_idx ON periods (period_start) WHERE state NOT IN ('RECONCILED', 'FAILED');

CREATE TABLE availability_minutes (
    minute timestamptz PRIMARY KEY,
    total  bigint      NOT NULL CHECK (total > 0),
    errors bigint      NOT NULL,
    busy   bigint      NOT NULL
);

CREATE TABLE availability_hours (
    hour             timestamptz PRIMARY KEY,
    measured_minutes integer     NOT NULL,
    bad_minutes      integer     NOT NULL,
    planned_minutes  integer     NOT NULL,
    total            bigint      NOT NULL,
    errors           bigint      NOT NULL,
    busy             bigint      NOT NULL
);

CREATE TABLE maintenance_windows (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    starts_at    timestamptz NOT NULL,
    ends_at      timestamptz NOT NULL CHECK (ends_at > starts_at),
    declared_at  timestamptz NOT NULL DEFAULT now(),
    cancelled_at timestamptz
);

CREATE INDEX maintenance_windows_ends_idx ON maintenance_windows (ends_at);
