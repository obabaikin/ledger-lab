CREATE TABLE invite_emails (
    email      text        PRIMARY KEY,
    token      text        NOT NULL REFERENCES invite_tokens,
    created_at timestamptz NOT NULL
);

CREATE TABLE invite_requests (
    email        text        NOT NULL REFERENCES invite_emails ON DELETE CASCADE,
    requested_at timestamptz NOT NULL
);

CREATE INDEX invite_requests_email_idx ON invite_requests (email, requested_at);

CREATE TABLE invite_mails (
    mail_id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email           text        REFERENCES invite_emails ON DELETE SET NULL,
    token           text        NOT NULL REFERENCES invite_tokens,
    kind            text        NOT NULL CHECK (kind IN ('LINK', 'LIMIT')),
    requested_at    timestamptz NOT NULL,
    window_frees_at timestamptz CHECK ((kind = 'LIMIT') = (window_frees_at IS NOT NULL)),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    sent_at         timestamptz,
    failed_at       timestamptz
);

CREATE INDEX invite_mails_due_idx ON invite_mails (next_attempt_at) WHERE sent_at IS NULL AND failed_at IS NULL;
CREATE INDEX invite_mails_requested_idx ON invite_mails (requested_at);
CREATE INDEX invite_mails_email_idx ON invite_mails (email, requested_at);
