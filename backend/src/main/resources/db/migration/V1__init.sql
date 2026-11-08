-- Stampede V1 schema (PRD section 9, with the M0.2 decisions recorded in docs/PLAN.md).
--
-- Conventions:
--   * ids are uuid. The app generates them with UUID.randomUUID(); gen_random_uuid() is only a
--     default for rows created directly in SQL.
--   * money is bigint paise, never negative. Timestamps are timestamptz (UTC).
--   * status columns are text + a named CHECK (easier to evolve than a Postgres ENUM).
--   * every constraint is named, so tests and error mapping can refer to it.
--   * foreign keys use the default ON DELETE RESTRICT: nothing is deleted as a side effect.
--
-- Postgres runs DDL transactionally, so if any statement here fails, none of V1 is applied.

-- ---------------------------------------------------------------------------------------------
-- Identity
-- ---------------------------------------------------------------------------------------------

CREATE TABLE users (
    id            uuid        NOT NULL DEFAULT gen_random_uuid(),
    email         text        NOT NULL,
    password_hash text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_users PRIMARY KEY (id)
);
-- Case-insensitive uniqueness without the citext extension.
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

CREATE TABLE devices (
    id               uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id          uuid        NOT NULL,
    fingerprint_hash text        NOT NULL,
    first_seen       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_devices PRIMARY KEY (id),
    CONSTRAINT fk_devices_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_devices_user_fingerprint UNIQUE (user_id, fingerprint_hash)
);

-- ---------------------------------------------------------------------------------------------
-- Venues and events
-- ---------------------------------------------------------------------------------------------

CREATE TABLE venues (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    name        text        NOT NULL,
    layout_json jsonb       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_venues PRIMARY KEY (id)
);

CREATE TABLE sections (
    id           uuid NOT NULL DEFAULT gen_random_uuid(),
    venue_id     uuid NOT NULL,
    code         text NOT NULL,
    -- A tier *name*, not a FK: tiers belong to events, sections belong to venues.
    -- The seed loader maps it to the event's price_tiers row.
    default_tier text NOT NULL,
    CONSTRAINT pk_sections PRIMARY KEY (id),
    CONSTRAINT fk_sections_venue FOREIGN KEY (venue_id) REFERENCES venues (id),
    CONSTRAINT uq_sections_venue_code UNIQUE (venue_id, code)
);

CREATE TABLE seats (
    id         uuid             NOT NULL DEFAULT gen_random_uuid(),
    section_id uuid             NOT NULL,
    row_label  text             NOT NULL,
    number     integer          NOT NULL,
    x          double precision NOT NULL,
    y          double precision NOT NULL,
    attrs      jsonb            NOT NULL DEFAULT '{}'::jsonb,  -- e.g. {"aisle": true, "accessible": false}
    CONSTRAINT pk_seats PRIMARY KEY (id),
    CONSTRAINT fk_seats_section FOREIGN KEY (section_id) REFERENCES sections (id),
    CONSTRAINT uq_seats_section_row_number UNIQUE (section_id, row_label, number),
    CONSTRAINT ck_seats_number_positive CHECK (number > 0)
);

CREATE TABLE events (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    venue_id       uuid        NOT NULL,
    title          text        NOT NULL,
    starts_at      timestamptz NOT NULL,
    drop_at        timestamptz NOT NULL,
    status         text        NOT NULL,
    per_user_limit integer     NOT NULL,
    strategy       text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_events PRIMARY KEY (id),
    CONSTRAINT fk_events_venue FOREIGN KEY (venue_id) REFERENCES venues (id),
    CONSTRAINT ck_events_status
        CHECK (status IN ('DRAFT', 'SCHEDULED', 'LIVE', 'PAUSED', 'SOLD_OUT', 'ENDED')),
    CONSTRAINT ck_events_strategy CHECK (strategy IN ('PESSIMISTIC', 'OPTIMISTIC', 'REDIS_FIRST')),
    CONSTRAINT ck_events_per_user_limit_positive CHECK (per_user_limit > 0),
    CONSTRAINT ck_events_drop_before_start CHECK (drop_at <= starts_at)
);

CREATE TABLE price_tiers (
    id          uuid   NOT NULL DEFAULT gen_random_uuid(),
    event_id    uuid   NOT NULL,
    name        text   NOT NULL,
    price_paise bigint NOT NULL,
    CONSTRAINT pk_price_tiers PRIMARY KEY (id),
    CONSTRAINT fk_price_tiers_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT uq_price_tiers_event_name UNIQUE (event_id, name),
    -- Target for composite FKs that must stay within one event.
    CONSTRAINT uq_price_tiers_id_event UNIQUE (id, event_id),
    CONSTRAINT ck_price_tiers_price_nonnegative CHECK (price_paise >= 0)
);

-- ---------------------------------------------------------------------------------------------
-- Holds and orders (created before event_seats, which points at both)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE holds (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    event_id   uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    -- Convenience list (hold -> seats). event_seats.hold_id is the authoritative owner of a seat.
    -- Array elements cannot have FKs; the invariant checker compares this with event_seats.
    seat_ids   uuid[]      NOT NULL,
    expires_at timestamptz NOT NULL,
    extended   boolean     NOT NULL DEFAULT false,  -- one 60 s extension allowed (PRD 6.3)
    status     text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_holds PRIMARY KEY (id),
    CONSTRAINT fk_holds_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT fk_holds_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_holds_id_event UNIQUE (id, event_id),
    CONSTRAINT ck_holds_status CHECK (status IN ('ACTIVE', 'CONVERTED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT ck_holds_seat_ids_not_empty CHECK (cardinality(seat_ids) > 0),
    CONSTRAINT ck_holds_seat_ids_no_nulls CHECK (array_position(seat_ids, NULL) IS NULL),
    CONSTRAINT ck_holds_expires_after_created CHECK (expires_at > created_at)
);
CREATE INDEX ix_holds_event_user ON holds (event_id, user_id);
-- The expiry sweeper scans only active holds by expiry time.
CREATE INDEX ix_holds_active_expires_at ON holds (expires_at) WHERE status = 'ACTIVE';

CREATE TABLE orders (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL,
    event_id    uuid        NOT NULL,
    status      text        NOT NULL,
    total_paise bigint      NOT NULL,
    saga_state  text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_orders_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT uq_orders_id_event UNIQUE (id, event_id),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'PAID', 'CANCELLED', 'REFUNDED')),
    CONSTRAINT ck_orders_saga_state CHECK (saga_state IN (
        'HOLD_VERIFIED', 'ORDER_CREATED', 'PAYMENT_INITIATED', 'PAYMENT_SUCCEEDED', 'SEATS_SOLD',
        'TICKETS_ISSUED', 'NOTIFIED', 'PAYMENT_FAILED', 'PAYMENT_TIMED_OUT', 'ORDER_CANCELLED',
        'SEATS_RELEASED', 'REFUND_INITIATED', 'REFUNDED')),
    CONSTRAINT ck_orders_total_nonnegative CHECK (total_paise >= 0)
);
CREATE INDEX ix_orders_user_event ON orders (user_id, event_id);
CREATE INDEX ix_orders_event ON orders (event_id);

-- ---------------------------------------------------------------------------------------------
-- Event seats: one row per seat per event, the contended table
-- ---------------------------------------------------------------------------------------------

CREATE TABLE event_seats (
    event_id uuid   NOT NULL,
    seat_id  uuid   NOT NULL,
    tier_id  uuid   NOT NULL,
    status   text   NOT NULL,
    version  bigint NOT NULL DEFAULT 0,  -- optimistic strategy: UPDATE ... WHERE version = ?
    hold_id  uuid,
    order_id uuid,
    CONSTRAINT pk_event_seats PRIMARY KEY (event_id, seat_id),
    CONSTRAINT fk_event_seats_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT fk_event_seats_seat FOREIGN KEY (seat_id) REFERENCES seats (id),
    -- Composite FKs: the tier, hold and order must all belong to this same event.
    -- (A NULL hold_id/order_id skips the check, which is what we want.)
    CONSTRAINT fk_event_seats_tier FOREIGN KEY (tier_id, event_id) REFERENCES price_tiers (id, event_id),
    CONSTRAINT fk_event_seats_hold FOREIGN KEY (hold_id, event_id) REFERENCES holds (id, event_id),
    CONSTRAINT fk_event_seats_order FOREIGN KEY (order_id, event_id) REFERENCES orders (id, event_id),
    -- Seat state machine (PRD 2, narrowed in M0.2): transitions are guarded in code (M1.1).
    CONSTRAINT ck_event_seats_status CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD')),
    CONSTRAINT ck_event_seats_version_nonnegative CHECK (version >= 0),
    -- Status must agree with the ownership pointers.
    CONSTRAINT ck_event_seats_status_pointers CHECK (
           (status = 'AVAILABLE' AND hold_id IS NULL AND order_id IS NULL)
        OR (status = 'HELD'      AND hold_id IS NOT NULL)
        OR (status = 'SOLD'      AND order_id IS NOT NULL))
);
CREATE INDEX ix_event_seats_event_status ON event_seats (event_id, status);
CREATE INDEX ix_event_seats_hold ON event_seats (hold_id) WHERE hold_id IS NOT NULL;
CREATE INDEX ix_event_seats_order ON event_seats (order_id) WHERE order_id IS NOT NULL;

-- Per-user ticket cap. Strategies increment `used` in the same transaction as the hold, with
-- UPDATE ... SET used = used + :n WHERE used + :n <= :limit, so concurrent requests serialise on
-- this one row. The upper bound lives in events.per_user_limit, so it is enforced in that UPDATE.
CREATE TABLE user_event_allowance (
    event_id uuid    NOT NULL,
    user_id  uuid    NOT NULL,
    used     integer NOT NULL DEFAULT 0,
    CONSTRAINT pk_user_event_allowance PRIMARY KEY (event_id, user_id),
    CONSTRAINT fk_user_event_allowance_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT fk_user_event_allowance_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_user_event_allowance_used_nonnegative CHECK (used >= 0)
);
CREATE INDEX ix_user_event_allowance_user ON user_event_allowance (user_id);

CREATE TABLE order_items (
    order_id    uuid    NOT NULL,
    event_id    uuid    NOT NULL,
    seat_id     uuid    NOT NULL,
    price_paise bigint  NOT NULL,
    -- true from order creation until the order is cancelled or refunded.
    active      boolean NOT NULL DEFAULT true,
    CONSTRAINT pk_order_items PRIMARY KEY (order_id, seat_id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id, event_id) REFERENCES orders (id, event_id),
    CONSTRAINT fk_order_items_event_seat FOREIGN KEY (event_id, seat_id) REFERENCES event_seats (event_id, seat_id),
    CONSTRAINT ck_order_items_price_nonnegative CHECK (price_paise >= 0)
);
-- LAST LINE OF DEFENCE (PRD 6.2): a seat can be in at most one active order, whatever the
-- inventory strategy does. Never drop or relax this index.
CREATE UNIQUE INDEX uq_order_items_active_seat ON order_items (event_id, seat_id) WHERE active;

-- ---------------------------------------------------------------------------------------------
-- Payments and idempotency
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payments (
    id           uuid        NOT NULL DEFAULT gen_random_uuid(),
    order_id     uuid        NOT NULL,
    provider_ref text,  -- NULL until the provider has created the intent
    status       text        NOT NULL,
    amount_paise bigint      NOT NULL,
    -- Every webhook as received. Append with raw_events = raw_events || ?, never read-modify-write.
    raw_events   jsonb       NOT NULL DEFAULT '[]'::jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_payments PRIMARY KEY (id),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT uq_payments_provider_ref UNIQUE (provider_ref),
    CONSTRAINT ck_payments_status CHECK (status IN ('CREATED', 'SUCCEEDED', 'FAILED', 'REFUNDED')),
    CONSTRAINT ck_payments_amount_nonnegative CHECK (amount_paise >= 0),
    CONSTRAINT ck_payments_raw_events_array CHECK (jsonb_typeof(raw_events) = 'array')
);
CREATE INDEX ix_payments_order ON payments (order_id);

CREATE TABLE idempotency_keys (
    key          text        NOT NULL,
    user_id      uuid        NOT NULL,
    request_hash text        NOT NULL,
    response     jsonb,  -- NULL while IN_PROGRESS
    status       text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    -- Scoped per user, so two users can never collide on the same key.
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (user_id, key),
    CONSTRAINT fk_idempotency_keys_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_idempotency_keys_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_idempotency_keys_completed_has_response
        CHECK (status <> 'COMPLETED' OR response IS NOT NULL)
);
CREATE INDEX ix_idempotency_keys_expires_at ON idempotency_keys (expires_at);

-- ---------------------------------------------------------------------------------------------
-- Outbox and consumer dedupe
-- ---------------------------------------------------------------------------------------------

CREATE TABLE outbox (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    aggregate_type text        NOT NULL,
    aggregate_id   uuid        NOT NULL,
    event_type     text        NOT NULL,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz,
    CONSTRAINT pk_outbox PRIMARY KEY (id)
);
-- The relay polls only unpublished rows, oldest first.
CREATE INDEX ix_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    consumer     text        NOT NULL,
    event_id     uuid        NOT NULL,  -- outbox.id; no FK, so the outbox can be pruned
    processed_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_events PRIMARY KEY (consumer, event_id)
);

-- ---------------------------------------------------------------------------------------------
-- Tickets and check-ins
-- ---------------------------------------------------------------------------------------------

CREATE TABLE tickets (
    id             uuid        NOT NULL DEFAULT gen_random_uuid(),
    order_id       uuid        NOT NULL,
    event_id       uuid        NOT NULL,
    seat_id        uuid        NOT NULL,
    holder_user_id uuid        NOT NULL,
    version        integer     NOT NULL DEFAULT 1,  -- transfer = version bump + new holder
    signature      bytea       NOT NULL,            -- Ed25519 over the payload (PRD 6.9)
    status         text        NOT NULL,
    issued_at      timestamptz NOT NULL DEFAULT now(),  -- part of the signed payload
    CONSTRAINT pk_tickets PRIMARY KEY (id),
    CONSTRAINT fk_tickets_order FOREIGN KEY (order_id, event_id) REFERENCES orders (id, event_id),
    CONSTRAINT fk_tickets_event_seat FOREIGN KEY (event_id, seat_id) REFERENCES event_seats (event_id, seat_id),
    CONSTRAINT fk_tickets_holder FOREIGN KEY (holder_user_id) REFERENCES users (id),
    CONSTRAINT ck_tickets_status CHECK (status IN ('ISSUED', 'CHECKED_IN', 'REVOKED')),
    CONSTRAINT ck_tickets_version_positive CHECK (version >= 1)
);
-- At most one live ticket per seat.
CREATE UNIQUE INDEX uq_tickets_live_seat ON tickets (event_id, seat_id) WHERE status <> 'REVOKED';
CREATE INDEX ix_tickets_holder ON tickets (holder_user_id);
CREATE INDEX ix_tickets_order ON tickets (order_id);

CREATE TABLE checkins (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    -- NULL only for INVALID_SIGNATURE scans: a forged QR may name a ticket that doesn't exist.
    ticket_id  uuid,
    gate       text        NOT NULL,
    scanned_at timestamptz NOT NULL,
    result     text        NOT NULL,
    CONSTRAINT pk_checkins PRIMARY KEY (id),
    CONSTRAINT fk_checkins_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id),
    CONSTRAINT ck_checkins_result CHECK (result IN ('VALID', 'DUPLICATE', 'INVALID_SIGNATURE')),
    CONSTRAINT ck_checkins_ticket_required
        CHECK (result = 'INVALID_SIGNATURE' OR ticket_id IS NOT NULL)
);
-- Duplicate scans are recorded as rows, but only one scan per ticket can be VALID.
CREATE UNIQUE INDEX uq_checkins_valid_ticket ON checkins (ticket_id) WHERE result = 'VALID';
CREATE INDEX ix_checkins_ticket ON checkins (ticket_id);

-- ---------------------------------------------------------------------------------------------
-- Audit log (append-only)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE audit_log (
    id     uuid        NOT NULL DEFAULT gen_random_uuid(),
    actor  text        NOT NULL,  -- a user id, or a system component name such as 'sweeper'
    action text        NOT NULL,
    entity text        NOT NULL,
    before jsonb,
    after  jsonb,
    at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_log PRIMARY KEY (id)
);
CREATE INDEX ix_audit_log_at ON audit_log (at);

-- Rejects any change to existing rows. Applies to every role, including the table owner;
-- only a superuser can bypass it (by disabling triggers). M7.1 adds REVOKE-based role separation.
CREATE FUNCTION audit_log_reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only: % is not allowed', TG_OP;
END;
$$;

-- Row-level: fires once per affected row on UPDATE/DELETE.
CREATE TRIGGER trg_audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_reject_change();

-- TRUNCATE doesn't fire row triggers, so it needs its own statement-level trigger.
CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_reject_change();
