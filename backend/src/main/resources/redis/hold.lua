-- Redis-first hold (PRD 6.2 C, D-022). Takes every requested seat for one hold, or changes nothing.
--
-- Redis runs a whole script as one command on its single command thread, so no other client can act
-- between a check below and the writes at the end. Redis does NOT undo a script that fails halfway,
-- so every check comes before the first write.
--
-- KEYS[1] event meta hash     KEYS[2] event seats set     KEYS[3] the user's holds (sorted set)
-- KEYS[4] the hold hash       KEYS[5] the inventory stream
-- ARGV[1] seat key prefix (seat key = prefix .. seatId)
-- ARGV[2] holdId   ARGV[3] userId   ARGV[4] eventId   ARGV[5] hold duration in ms
-- ARGV[6..] seat ids
--
-- Replies (arrays of strings):
--   {'HELD', expiresMs, createdMs}      {'NOT_LOADED'}      {'DUPLICATE_HOLD'}
--   {'UNKNOWN_SEAT', seatId...}         {'SEAT_TAKEN', takenAgoMs, seatId...}      {'LIMIT_EXCEEDED', limit}

local seatPrefix = ARGV[1]
local holdId = ARGV[2]
local userId = ARGV[3]
local eventId = ARGV[4]
local holdForMs = tonumber(ARGV[5])

-- 1. The event must have been loaded from Postgres (its seat list and per-user limit).
local limit = tonumber(redis.call('HGET', KEYS[1], 'limit'))
if not limit then
    return {'NOT_LOADED'}
end

-- Redis' own clock is the authority for this strategy's expiry (D-015).
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

-- 2. Seats that are not seats of this event.
local unknown = {'UNKNOWN_SEAT'}
for i = 6, #ARGV do
    if redis.call('SISMEMBER', KEYS[2], ARGV[i]) == 0 then
        unknown[#unknown + 1] = ARGV[i]
    end
end
if #unknown > 1 then
    return unknown
end

-- 3. Seats somebody already has: a seat key exists while a seat is held (it expires with the hold)
--    or sold (no expiry). Its value is 'H:<holdId>:<createdMs>' or 'S:<orderId>:<soldMs>'.
local taken = {'SEAT_TAKEN', '0'}
local newestTakenMs = 0
for i = 6, #ARGV do
    local owner = redis.call('GET', seatPrefix .. ARGV[i])
    if owner then
        taken[#taken + 1] = ARGV[i]
        local since = tonumber(string.match(owner, ':(%d+)$'))
        if since and since > newestTakenMs then
            newestTakenMs = since
        end
    end
end
if #taken > 2 then
    taken[2] = string.format('%.0f', math.max(0, now - newestTakenMs))
    return taken
end

-- 4. Per-user cap (D-005). Not a counter that someone must remember to decrement: the user's seats
--    are recounted from their live holds. Members are '<holdId>:<seat count>' scored by expiry time,
--    so a hold stops counting at the moment its seat keys expire.
local used = 0
for _, member in ipairs(redis.call('ZRANGEBYSCORE', KEYS[3], now, '+inf')) do
    used = used + tonumber(string.match(member, ':(%d+)$'))
end
local seatCount = #ARGV - 5
if used + seatCount > limit then
    return {'LIMIT_EXCEEDED', string.format('%.0f', limit)}
end

-- A hold id is new for every attempt (D-015); a live one being reused is a caller bug.
if redis.call('EXISTS', KEYS[4]) == 1 then
    return {'DUPLICATE_HOLD'}
end

-- 5. Every check passed: write. Everything of this hold expires at the same absolute millisecond.
local expires = now + holdForMs
local nowText = string.format('%.0f', now)
local expiresText = string.format('%.0f', expires)
local seats = {}
for i = 6, #ARGV do
    redis.call('SET', seatPrefix .. ARGV[i], 'H:' .. holdId .. ':' .. nowText, 'PXAT', expires)
    seats[#seats + 1] = ARGV[i]
end
local seatList = table.concat(seats, ',')

redis.call('HSET', KEYS[4], 'user', userId, 'seats', seatList, 'n', seatCount,
        'createdMs', nowText, 'expiresMs', expiresText)
redis.call('PEXPIREAT', KEYS[4], expires)

redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', '(' .. nowText)   -- drop the user's expired holds
redis.call('ZADD', KEYS[3], expires, holdId .. ':' .. seatCount)
local last = redis.call('ZRANGE', KEYS[3], -1, -1, 'WITHSCORES')
if last[2] == 'inf' then
    redis.call('PERSIST', KEYS[3])                                -- a sold member never expires (M2.2c)
else
    redis.call('PEXPIREAT', KEYS[3], tonumber(last[2]))
end

-- The entry the Postgres consumer reads (M2.2b). Written in the same atomic step as the hold, so a
-- hold can't exist without its entry.
redis.call('XADD', KEYS[5], '*', 'type', 'HELD', 'event', eventId, 'hold', holdId, 'user', userId,
        'seats', seatList, 'createdMs', nowText, 'expiresMs', expiresText)

return {'HELD', expiresText, nowText}
