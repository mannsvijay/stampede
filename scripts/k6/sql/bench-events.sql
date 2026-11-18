-- The events of one k6 run (M2.4a, D-024 decision D3-A), run by scripts/k6.sh through psql in the postgres
-- container. Each run gets two fresh events, copied from the seeded demo event (its venue, tiers and
-- seats), so no run ever needs a reset:
--   warm-up    on sale now, created with the strategy under test: warm-up traffic, never measured
--   benchmark  created PESSIMISTIC with its drop :lead seconds away; setup() switches it to the strategy
--              under test through POST /admin/events/{id}/strategy (D-023) before the drop
-- Only inserts, in one transaction: every seat starts AVAILABLE, nothing skips the seat state machine.
-- Titles start with "k6 " so end-bench-events.sql can find them.
--
-- psql variables: run (run id), strategy, lead (seconds), demo_title (the seed file's event title).
-- Prints one line per event: warmup=<id> and bench=<id>.

\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE k6_source ON COMMIT DROP AS
    SELECT id, venue_id, per_user_limit FROM events WHERE title = :'demo_title';

DO $$
BEGIN
    IF (SELECT count(*) FROM k6_source) <> 1 THEN
        RAISE EXCEPTION 'expected exactly one seeded demo event; start the backend with the seed profile';
    END IF;
END
$$;

CREATE TEMP TABLE k6_events ON COMMIT DROP AS
    SELECT 'warmup' AS kind, gen_random_uuid() AS id, :'strategy'::text AS strategy,
           now() - interval '1 minute' AS drop_at, 'k6 warm-up ' || :'run' AS title
    UNION ALL
    SELECT 'bench', gen_random_uuid(), 'PESSIMISTIC',
           now() + make_interval(secs => :lead), 'k6 bench ' || :'run';

INSERT INTO events (id, venue_id, title, starts_at, drop_at, status, per_user_limit, strategy)
SELECT e.id, s.venue_id, e.title, now() + interval '7 days', e.drop_at, 'SCHEDULED', s.per_user_limit, e.strategy
FROM k6_events e CROSS JOIN k6_source s;

INSERT INTO price_tiers (id, event_id, name, price_paise)
SELECT gen_random_uuid(), e.id, t.name, t.price_paise
FROM k6_events e CROSS JOIN k6_source s JOIN price_tiers t ON t.event_id = s.id;

INSERT INTO event_seats (event_id, seat_id, tier_id, status)
SELECT e.id, es.seat_id, nt.id, 'AVAILABLE'
FROM k6_events e
CROSS JOIN k6_source s
JOIN event_seats es ON es.event_id = s.id
JOIN price_tiers ot ON ot.id = es.tier_id
JOIN price_tiers nt ON nt.event_id = e.id AND nt.name = ot.name;

SELECT kind || '=' || id FROM k6_events ORDER BY kind DESC;

COMMIT;
