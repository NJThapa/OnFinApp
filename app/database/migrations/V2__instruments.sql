-- Stocks and ETFs, synced from the price provider. Symbols are stored in upper case.
CREATE TABLE instruments (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol     text        NOT NULL,
    name       text        NOT NULL,
    type       text        NOT NULL CHECK (type IN ('STOCK', 'ETF')),
    exchange   text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT instruments_symbol_key UNIQUE (symbol)
);

-- Instruments a user follows.
CREATE TABLE watchlist_items (
    user_id       uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    instrument_id uuid        NOT NULL REFERENCES instruments (id) ON DELETE CASCADE,
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, instrument_id)
);

-- Price-provider responses, shared by both APIs.
-- range is 'QUOTE' for the latest quote, otherwise a chart range: 1D, 1W, 1M, 1Y, 5Y.
CREATE TABLE price_cache (
    instrument_id uuid        NOT NULL REFERENCES instruments (id) ON DELETE CASCADE,
    range         text        NOT NULL,
    payload       jsonb       NOT NULL,
    fetched_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (instrument_id, range)
);
