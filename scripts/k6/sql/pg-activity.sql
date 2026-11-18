-- One sample of the application's database connections (M2.4a, D-024 decision D6-A), taken once a second
-- by scripts/k6.sh during a run: one CSV line (time, connections, active, waiting_on_lock,
-- idle_in_transaction, idle). Only the backend's pool (the JDBC driver's application_name). When every
-- pooled connection is active and waiting on a lock (Hikari's default pool is 10), the pool is
-- exhausted: further requests wait up to 30 s for a connection (the M1.2 review note).
SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
       count(*),
       count(*) FILTER (WHERE state = 'active'),
       count(*) FILTER (WHERE state = 'active' AND wait_event_type = 'Lock'),
       count(*) FILTER (WHERE state LIKE 'idle in transaction%'),
       count(*) FILTER (WHERE state = 'idle')
FROM pg_stat_activity
WHERE datname = current_database()
  AND backend_type = 'client backend'
  AND application_name = 'PostgreSQL JDBC Driver';
