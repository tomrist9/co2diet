CREATE TABLE food_product (
                              id            BIGINT PRIMARY KEY,
                              barcode       TEXT UNIQUE,
                              name          TEXT   NOT NULL,
                              brand         TEXT,
                              category_code TEXT,
                              row_version   BIGINT NOT NULL
);
CREATE INDEX idx_product_row_version ON food_product (row_version);