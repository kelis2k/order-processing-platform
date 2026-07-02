CREATE TABLE email_confirmation_tokens (
                                           id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                           user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
                                           token_hash VARCHAR(64)  NOT NULL UNIQUE,
                                           expires_at TIMESTAMPTZ  NOT NULL,
                                           used       BOOLEAN      NOT NULL DEFAULT FALSE,
                                           created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_email_confirmation_tokens_user_id ON email_confirmation_tokens (user_id);