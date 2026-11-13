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
local hold = redis.call('HMGET', KEYS[1], 'user', 'seats', 'n', 'expiresMs')
if not hold[1] then
    return {'GONE'}
end

-- The hash still exists, so by Redis' clock the hold has not expired: an EXPIRED release (the
-- sweeper) must leave it alone. Expiry itself needs no script; the keys' TTL does it.
if reason == 'EXPIRED' then
    return {'NOT_EXPIRED', hold[4]}
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

redis.call('XADD', KEYS[2], '*', 'type', 'RELEASED', 'event', eventId, 'hold', holdId, 'user', hold[1],
        'seats', hold[2], 'reason', reason)

return released
