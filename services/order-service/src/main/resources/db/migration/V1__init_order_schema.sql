CREATE TABLE orders (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      VARCHAR(64) NOT NULL,
    status       VARCHAR(20) NOT NULL,
    total_amount NUMERIC(12, 2),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      INTEGER     NOT NULL DEFAULT 0
);

CREATE TABLE order_items (
    id         BIGSERIAL PRIMARY KEY,
    order_id   UUID        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id VARCHAR(24) NOT NULL,
    quantity   INTEGER     NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(12, 2),
    UNIQUE (order_id, product_id)
);

CREATE TABLE saga_state (
    order_id   UUID PRIMARY KEY REFERENCES orders (id) ON DELETE CASCADE,
    state      VARCHAR(32) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id   VARCHAR(64)   NOT NULL,
    event_type     VARCHAR(64)   NOT NULL,
    topic          VARCHAR(64)   NOT NULL,
    msg_key        VARCHAR(64)   NOT NULL,
    payload        JSONB         NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;