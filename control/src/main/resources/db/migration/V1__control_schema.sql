CREATE TABLE invite_tokens (
    token      text        PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    quota      integer     NOT NULL CHECK (quota > 0),
    active     boolean     NOT NULL DEFAULT true
);

CREATE TABLE invite_visits (
    token      text        PRIMARY KEY REFERENCES invite_tokens,
    first_seen timestamptz NOT NULL,
    last_seen  timestamptz NOT NULL,
    count      bigint      NOT NULL
);

CREATE TABLE sessions (
    session_id uuid        PRIMARY KEY,
    token      text        NOT NULL REFERENCES invite_tokens,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE runs (
    run_id           uuid        PRIMARY KEY,
    session_id       uuid        NOT NULL REFERENCES sessions,
    token            text        NOT NULL REFERENCES invite_tokens,
    request_id       uuid        NOT NULL,
    tps              integer     NOT NULL,
    duration_seconds integer     NOT NULL,
    state            text        NOT NULL CHECK (state IN ('QUEUED', 'CREATED', 'RUNNING', 'DRAINING', 'RECONCILED', 'FAILED', 'EXPIRED')),
    created_at       timestamptz NOT NULL,
    admitted_at      timestamptz,
    finished_at      timestamptz,
    UNIQUE (session_id, request_id)
);

CREATE INDEX runs_queue_idx ON runs (created_at, run_id) WHERE state = 'QUEUED';
CREATE INDEX runs_token_idx ON runs (token, created_at);
