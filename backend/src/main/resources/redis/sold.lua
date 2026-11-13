-- Redis-first sale, step 3 of 3 (D-022, M2.2c): records in Redis a sale that Postgres has COMMITTED.
-- The strategy runs it right after the Postgres commit; reconciler rule R4 runs it again for a
-- CONVERTED hold whose hash Redis still has (the first run failed).
--
-- In one atomic step: every seat of the hold becomes 'S:<orderId>:<soldMs>' with no expiry, the user's
-- member '<holdId>:<n>' gets score +inf (bought seats count towards the cap forever, D-016), and the
-- hold hash is deleted. If the hash survived, rule R4 would see a Redis hold whose Postgres row is
-- CONVERTED; deleting it here is what lets R4 tell "finished" from "not finished".
--
-- Postgres has sold these seats, so a seat key naming another hold is overwritten (Postgres wins); that
-- hold's id is returned so the caller can release it in Redis, as reconciler rule R3 does.
--
-- KEYS[1] the hold hash
-- ARGV[1] seat key prefix     ARGV[2] user key prefix     ARGV[3] holdId     ARGV[4] orderId
--
-- Replies: {'SOLD', soldMs, otherHoldId...}     {'GONE'}

local seatPrefix = ARGV[1]
local userPrefix = ARGV[2]
local holdId = ARGV[3]
local orderId = ARGV[4]

local hold = redis.call('HMGET', KEYS[1], 'user', 'seats', 'n')
if not hold[1] then
    return {'GONE'}
end

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local nowText = string.format('%.0f', now)

local reply = {'SOLD', nowText}
local mine = 'H:' .. holdId .. ':'
for seatId in string.gmatch(hold[2], '[^,]+') do
    local key = seatPrefix .. seatId
    local owner = redis.call('GET', key)
    if owner and string.sub(owner, 1, 2) == 'H:' and string.sub(owner, 1, #mine) ~= mine then
        reply[#reply + 1] = string.match(owner, '^H:([^:]+):')
    end
    redis.call('SET', key, 'S:' .. orderId .. ':' .. nowText)   -- SET without PX: no expiry
end

local userKey = userPrefix .. hold[1]
redis.call('ZADD', userKey, '+inf', holdId .. ':' .. hold[3])
redis.call('PERSIST', userKey)
redis.call('DEL', KEYS[1])

return reply
