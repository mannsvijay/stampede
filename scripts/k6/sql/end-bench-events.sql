-- Ends every k6 event that isn't ended yet (M2.4a, D-024), run by scripts/k6.sh after a run and before
-- the next one (in case a run was aborted). The invariant monitor checks every event that is neither
-- DRAFT nor ENDED every 30 s, so finished k6 events would otherwise add to the load of later runs.
-- Events have no lifecycle code yet (no transitions are guarded in the application), so this is a plain
-- status write; it touches only events whose title starts with "k6 ", which only bench-events.sql makes.

\set ON_ERROR_STOP on

UPDATE events SET status = 'ENDED' WHERE title LIKE 'k6 %' AND status <> 'ENDED';
