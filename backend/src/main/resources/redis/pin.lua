-- Redis-first sale, step 1 of 3 (D-022, M2.2c): pins a live hold so it can't expire while Postgres
-- records the sale. Atomic like hold.lua: checks first, then writes.
--
-- The strategy runs this while it holds the hold's Postgres row lock, then sells in Postgres, then runs
-- sold.lua. "Pinning" removes the TTL from the hold hash and from this hold's seat keys: the seats stay
-- taken in Redis until sold.lua turns them into S: markers (sale committed) or release.lua frees them
-- (sale failed, or the sweeper after a crash: see release.lua's EXPIRED case). The user's member is set
-- to +inf at the same time, so the cap counts these seats for as long as the sale might still commit.
--
-- KEYS[1] the hold hash
-- ARGV[1] seat key prefix     ARGV[2] user key prefix (user key = prefix .. userId)     ARGV[3] holdId
--
-- Replies (arrays of strings):
--   {'PINNED', expiresMs, seatId...}     {'EXPIRED', expiresMs}     {'GONE'}

local seatPrefix = ARGV[1]
local userPrefix = ARGV[2]
local holdId = ARGV[3]

-- No hash: the hold expired by its TTL, was released, or never existed.
local hold = redis.call('HMGET', KEYS[1], 'user', 'seats', 'n', 'expiresMs')
if not hold[1] then
    return {'GONE'}
end

-- A hash that outlived its expiry was pinned by an earlier sale that never finished. Its time has run
-- out by Redis' clock, so it must not be sold now; the sweeper releases it.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if tonumber(hold[4]) <= now then
    return {'EXPIRED', hold[4]}
end

redis.call('PERSIST', KEYS[1])
local pinned = {'PINNED', hold[4]}
local mine = 'H:' .. holdId .. ':'
for seatId in string.gmatch(hold[2], '[^,]+') do
    local key = seatPrefix .. seatId
    local owner = redis.call('GET', key)
    if owner and string.sub(owner, 1, #mine) == mine then
        redis.call('PERSIST', key)
    end
    pinned[#pinned + 1] = seatId
end

local userKey = userPrefix .. hold[1]
redis.call('ZADD', userKey, '+inf', holdId .. ':' .. hold[3])
redis.call('PERSIST', userKey)

return pinned
