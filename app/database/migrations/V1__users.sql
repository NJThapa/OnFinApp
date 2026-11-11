-- Local user record, created on a user's first authenticated request.
-- Identity lives in the OIDC provider; (issuer, subject) is the stable key.
CREATE TABLE users (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    issuer     text        NOT NULL,
    subject    text        NOT NULL,
    email      text,
    name       text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT users_issuer_subject_key UNIQUE (issuer, subject)
);
