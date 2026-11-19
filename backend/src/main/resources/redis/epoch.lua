-- The check a Redis seat-map read makes before and after its MGETs (M2.4a-seatmap, D-020): Redis' clock,
-- whether the event is loaded or its gate closed, and its load epoch, all at one instant. Read-only.
--
-- KEYS[1] event meta hash     KEYS[2] the event's closed gate
--
-- Reply: {nowMs, state, loadedMs}
--   state 'CLOSED' (a strategy switch is under way or done, D-023), 'NOT_LOADED' (no meta: Redis doesn't
--   have the event, so a missing seat key means nothing) or 'LOADED'; loadedMs is the load epoch, written
--   by load.lua ('' for an event loaded without one).

local time = redis.call('TIME')
local now = string.format('%.0f', tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000))

if redis.call('EXISTS', KEYS[2]) == 1 then
    return {now, 'CLOSED', ''}
end
if redis.call('EXISTS', KEYS[1]) == 0 then
    return {now, 'NOT_LOADED', ''}
end
local epoch = redis.call('HGET', KEYS[1], 'loadedMs')
return {now, 'LOADED', epoch or ''}
