-- Stampede V2: auth (M0.5, D-008). Same conventions as V1.

-- ---------------------------------------------------------------------------------------------
-- users.role (deferred from M0.2)
-- ---------------------------------------------------------------------------------------------

-- Existing rows become USER. The first ADMIN is created by the bootstrap-admin profile.
ALTER TABLE users
    ADD COLUMN role text NOT NULL DEFAULT 'USER',
    ADD CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'));

-- ---------------------------------------------------------------------------------------------
-- Refresh tokens (not in PRD section 9)
-- ---------------------------------------------------------------------------------------------

-- One row per login session. A login starts a family; every refresh adds a token to it.
-- The family row is also the lock: every rotation and every revocation first locks it
-- (SELECT ... FOR UPDATE), so operations on one family run one at a time. Revoking is a single-row
-- update, so a refresh racing a logout either finishes first (and its new token dies with the
-- family) or waits and sees the revocation.
CREATE TABLE refresh_token_families (
    id            uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    -- The 30-day cap: no refresh can extend a session past this.
    expires_at    timestamptz NOT NULL,
    revoked_at    timestamptz,
    revoke_reason text,
    CONSTRAINT pk_refresh_token_families PRIMARY KEY (id),
    CONSTRAINT fk_refresh_token_families_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_refresh_token_families_expiry CHECK (created_at < expires_at),
    CONSTRAINT ck_refresh_token_families_revoke_reason CHECK (revoke_reason IN ('REUSE', 'LOGOUT')),
    -- revoked_at and revoke_reason are set together or not at all.
    CONSTRAINT ck_refresh_token_families_revoked_consistent
        CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);
CREATE INDEX ix_refresh_token_families_user ON refresh_token_families (user_id);

-- One row per refresh token ever issued. The token itself is never stored, only its SHA-256.
-- Each refresh consumes its token (used_at) and inserts the child (replaced_by). Presenting a
-- consumed token again revokes the family (reuse detection).
-- All times come from the database clock (now()), so every API instance agrees on expiry.
CREATE TABLE refresh_tokens (
    id          uuid        NOT NULL,
    family_id   uuid        NOT NULL,
    token_hash  bytea       NOT NULL,
    issued_at   timestamptz NOT NULL DEFAULT now(),
    -- Never later than the family's expires_at (set by the app; a CHECK can't read another table).
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    replaced_by uuid,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT fk_refresh_tokens_family FOREIGN KEY (family_id) REFERENCES refresh_token_families (id),
    CONSTRAINT fk_refresh_tokens_replaced_by FOREIGN KEY (replaced_by) REFERENCES refresh_tokens (id),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_hash_length CHECK (octet_length(token_hash) = 32),
    CONSTRAINT ck_refresh_tokens_expiry CHECK (issued_at < expires_at),
    -- Only a consumed token can have a child.
    CONSTRAINT ck_refresh_tokens_replaced_only_if_used CHECK (replaced_by IS NULL OR used_at IS NOT NULL)
);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
