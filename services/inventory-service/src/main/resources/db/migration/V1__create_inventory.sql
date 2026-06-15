CREATE TABLE inventory (
    id BIGSERIAL PRIMARY KEY,
    product_id VARCHAR(24) NOT NULL UNIQUE,
    available INTEGER NOT NULL DEFAULT 0 CHECK (available >= 0),
    reserved INTEGER NOT NULL DEFAULT 0 CHECK ( reserved >= 0 ),
    version INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_inventory_product_id ON inventory (product_id);