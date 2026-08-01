ALTER TABLE reservations
    ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'RESERVED';

CREATE TABLE reservation_items (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL REFERENCES reservations (order_id) ON DELETE CASCADE,
    product_id VARCHAR(24) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    UNIQUE (order_id, product_id)
);

CREATE INDEX idx_reservation_items_order_id ON reservation_items (order_id);
