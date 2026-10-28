ALTER TABLE invite_emails ADD COLUMN delete_at timestamptz;

CREATE INDEX invite_emails_delete_at_idx ON invite_emails (delete_at) WHERE delete_at IS NOT NULL;
CREATE INDEX invite_mails_token_idx ON invite_mails (token);
