CREATE TABLE outbox (
                        id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        aggregate_type VARCHAR(32)  NOT NULL,
                        aggregate_id   VARCHAR(64)  NOT NULL,
                        event_type     VARCHAR(64)  NOT NULL,
                        topic          VARCHAR(64)  NOT NULL,
                        msg_key        VARCHAR(64)  NOT NULL,
                        payload        JSONB        NOT NULL,
                        created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
                        published_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;