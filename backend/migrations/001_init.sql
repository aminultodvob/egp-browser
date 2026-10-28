-- e-GP Browser backend: accounts, trials, PayStation orders and lifetime entitlements.
-- Requires PostgreSQL 13+ (gen_random_uuid is built in).

CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    google_sub    text NOT NULL UNIQUE,
    email         text NOT NULL,
    display_name  text,
    status        text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz
);

-- Opaque refresh tokens: only SHA-256 hashes are stored. Rotated on every use;
-- reuse of a spent token revokes the whole family (token theft detection).
CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id   uuid NOT NULL,
    token_hash  bytea NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    revoked_at  timestamptz
);
CREATE INDEX refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family ON refresh_tokens (family_id);

-- One trial per account; times come from the database clock, never the device.
CREATE TABLE trials (
    user_id    uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    started_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CHECK (expires_at > started_at)
);

-- Pseudonymous trial history (HMAC of Google subject and device ID). Survives account
-- deletion so deleting and re-creating an account cannot restart the trial.
CREATE TABLE trial_claims (
    subject_hash bytea PRIMARY KEY,
    device_hash  bytea,
    claimed_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX trial_claims_device ON trial_claims (device_hash);

CREATE TABLE payment_orders (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_number   text NOT NULL UNIQUE,
    user_id          uuid REFERENCES users (id) ON DELETE SET NULL,
    amount           numeric(12, 2) NOT NULL CHECK (amount > 0),
    currency         text NOT NULL DEFAULT 'BDT',
    provider         text NOT NULL DEFAULT 'paystation',
    status           text NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'PAID', 'FAILED', 'CANCELLED',
                                                      'EXPIRED', 'REFUNDED', 'VERIFICATION_REQUIRED')),
    payment_url      text,
    provider_trx_id  text UNIQUE,
    provider_status  text,
    payment_method   text,
    failure_reason   text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    last_checked_at  timestamptz,
    verified_at      timestamptz,
    paid_at          timestamptz
);
CREATE INDEX payment_orders_user ON payment_orders (user_id, created_at DESC);
CREATE INDEX payment_orders_open ON payment_orders (status) WHERE status IN ('PENDING', 'PROCESSING', 'VERIFICATION_REQUIRED');
-- A user can never have two paid orders: a second successful payment is flagged for refund instead.
CREATE UNIQUE INDEX payment_orders_one_paid_per_user ON payment_orders (user_id) WHERE status = 'PAID';

CREATE TABLE entitlements (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind            text NOT NULL CHECK (kind IN ('LIFETIME')),
    status          text NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    source_order_id uuid NOT NULL REFERENCES payment_orders (id),
    granted_at      timestamptz NOT NULL DEFAULT now(),
    revoked_at      timestamptz,
    revoke_reason   text,
    UNIQUE (user_id, kind)
);

-- Append-only audit trail: raw IPNs, provider checks, state changes, grants and revocations.
CREATE TABLE payment_events (
    id             bigserial PRIMARY KEY,
    order_id       uuid REFERENCES payment_orders (id) ON DELETE SET NULL,
    invoice_number text,
    kind           text NOT NULL,
    detail         jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_events_order ON payment_events (order_id, created_at);
