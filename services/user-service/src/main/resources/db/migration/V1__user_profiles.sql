CREATE TABLE user_profiles (
                               id         UUID PRIMARY KEY,
                               email      VARCHAR(255) NOT NULL UNIQUE,
                               username   VARCHAR(100) NOT NULL,
                               role       VARCHAR(20)  NOT NULL DEFAULT 'ROLE_USER',
                               created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
                               updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);