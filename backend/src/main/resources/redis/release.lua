-- Redis-first release (D-022). Frees a live hold's seats, or changes nothing. Atomic like hold.lua.
--
-- KEYS[1] the hold hash       KEYS[2] the inventory stream
-- ARGV[1] seat key prefix     ARGV[2] user key prefix (user key = prefix .. userId)
-- ARGV[3] holdId   ARGV[4] eventId   ARGV[5] reason: RELEASED | EXPIRED | SAGA_FAILED
--
-- Replies (arrays of strings):
--   {'RELEASED', seatId...}     {'NOT_EXPIRED', expiresMs}     {'GONE'}

local seatPrefix = ARGV[1]
local userPrefix = ARGV[2]
local holdId = ARGV[3]
local eventId = ARGV[4]
local reason = ARGV[5]

-- No hash: the hold expired (Redis deleted it), was released, or never existed. Redis alone can't
-- tell which; Postgres can (M2.2c).
local hold = redis.call('HMGET', KEYS[1], 'user', 'seats', 'n', 'expiresMs', 'createdMs')
if not hold[1] then
    return {'GONE'}
end

-- The hash still exists. Normally that means the hold has not expired by Redis' clock, so an EXPIRED
-- release (the sweeper) must leave it alone: expiry itself needs no script, the keys' TTL does it.
-- The one exception (M2.2c): a hold pinned by pin.lua has no TTL. If its sale never finished (the
-- process crashed after the pin), the hash outlives its expiry, and then the sweeper releases it here.
if reason == 'EXPIRED' then
    local time = redis.call('TIME')
    local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
    if tonumber(hold[4]) > now then
        return {'NOT_EXPIRED', hold[4]}
    end
end

local released = {'RELEASED'}
local mine = 'H:' .. holdId .. ':'
for seatId in string.gmatch(hold[2], '[^,]+') do
    local key = seatPrefix .. seatId
    local owner = redis.call('GET', key)
    -- Only delete a seat key that still names this hold, never another owner's.
    if owner and string.sub(owner, 1, #mine) == mine then
        redis.call('DEL', key)
    end
    released[#released + 1] = seatId
end

redis.call('ZREM', userPrefix .. hold[1], holdId .. ':' .. hold[3])   -- gives the seats back to the cap
redis.call('DEL', KEYS[1])

-- createdMs and expiresMs (M2.2b): if this entry reaches Postgres before the hold's HELD entry, the
-- consumer records the hold as already over (a tombstone row), and the holds row needs both times.
-- An EXPIRED release (a pinned hold, above) writes no entry: the sweeper that asked for it closes the
-- Postgres row itself, and if that fails it asks again on its next tick (Redis then answers GONE).
if reason ~= 'EXPIRED' then
    redis.call('XADD', KEYS[2], '*', 'type', 'RELEASED', 'event', eventId, 'hold', holdId, 'user', hold[1],
            'seats', hold[2], 'reason', reason, 'createdMs', hold[5], 'expiresMs', hold[4])
end

return released
