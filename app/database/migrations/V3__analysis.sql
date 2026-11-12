-- One generated analysis of an instrument, shared by all users. Rows are never updated.
-- content holds summary, bullPoints, bearPoints, risks and keyFigures; sources is a list of {title, url}.
CREATE TABLE analyses (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    instrument_id uuid        NOT NULL REFERENCES instruments (id) ON DELETE CASCADE,
    content       jsonb       NOT NULL,
    sources       jsonb       NOT NULL,
    generated_at  timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL,
    -- Which API produced it, the LLM model, how long the LLM call took and what it used.
    generated_by  text        NOT NULL CHECK (generated_by IN ('java', 'dotnet')),
    model         text        NOT NULL,
    duration_ms   integer     NOT NULL,
    input_tokens  integer     NOT NULL,
    output_tokens integer     NOT NULL
);

CREATE INDEX analyses_instrument_idx ON analyses (instrument_id, generated_at DESC);

-- One rating per analysis. Append-only: seq gives the order, newest last.
CREATE TABLE ratings (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    seq           bigint GENERATED ALWAYS AS IDENTITY,
    instrument_id uuid        NOT NULL REFERENCES instruments (id) ON DELETE CASCADE,
    analysis_id   uuid        NOT NULL REFERENCES analyses (id) ON DELETE CASCADE,
    rating        text        NOT NULL CHECK (rating IN ('BUY', 'HOLD', 'SELL')),
    confidence    text        NOT NULL CHECK (confidence IN ('LOW', 'MEDIUM', 'HIGH')),
    rationale     text        NOT NULL,
    created_at    timestamptz NOT NULL,
    CONSTRAINT ratings_seq_key UNIQUE (seq),
    CONSTRAINT ratings_analysis_key UNIQUE (analysis_id)
);

CREATE INDEX ratings_instrument_idx ON ratings (instrument_id, seq DESC);

-- Work queue for generation. Both APIs claim rows with FOR UPDATE SKIP LOCKED, so a job runs once.
-- attempts counts how many times the job was claimed.
CREATE TABLE generation_jobs (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind           text        NOT NULL CHECK (kind IN ('ANALYSIS')),
    instrument_id  uuid        NOT NULL REFERENCES instruments (id) ON DELETE CASCADE,
    status         text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    trigger        text        NOT NULL CHECK (trigger IN ('ON_DEMAND', 'SCHEDULED', 'USER')),
    -- The user who asked for a refresh (trigger USER); counted against the per-user refresh limit.
    requested_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    -- Correlation ID of the request that queued the job, so the worker's log lines can be tied to it.
    correlation_id text,
    claimed_by     text,
    claimed_at     timestamptz,
    attempts       integer     NOT NULL DEFAULT 0,
    error          text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    finished_at    timestamptz
);

-- At most one unfinished job per instrument: only one generation per instrument runs at a time.
CREATE UNIQUE INDEX generation_jobs_active_key ON generation_jobs (kind, instrument_id)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE INDEX generation_jobs_instrument_idx ON generation_jobs (instrument_id, created_at DESC);
CREATE INDEX generation_jobs_requested_by_idx ON generation_jobs (requested_by, created_at)
    WHERE trigger = 'USER';
