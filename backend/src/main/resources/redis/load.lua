-- Loads one event from a Postgres snapshot into Redis (D-022, M2.2b). All of it, or nothing.
--
-- Atomic like hold.lua: Redis runs the whole script as one command, so no hold can be taken while the
-- event is half loaded, and two loaders running at once can't interleave. The second one finds the
-- meta hash and changes nothing.
--
-- If meta already exists the event is loaded and this script writes NOTHING. In particular it never
-- restores a hold into a loaded event: a Postgres ACTIVE hold that Redis lacks may have been released
-- in Redis with its RELEASED entry not applied yet, and restoring it would bring it back to life.
--
-- KEYS[1] event meta hash     KEYS[2] event seats set
-- ARGV[1] seat key prefix     ARGV[2] user key prefix     ARGV[3] hold key prefix
-- ARGV[4] per-user limit
-- Then four counted lists, each starting with its item count:
--   seats:        seatId
--   sold seats:   seatId, orderId, soldMs                      (3 values per item)
--   sold members: userId, '<holdId>:<seat count>'             (2 values per item)
--   holds:        holdId, userId, createdMs, expiresMs, 'seat,seat,...', seat count   (6 per item)
--
-- Replies: {'ALREADY_LOADED'}      {'LOADED', restoredHolds, skippedHolds, loadedMs}

if redis.call('EXISTS', KEYS[1]) == 1 then
    return {'ALREADY_LOADED'}
end

local seatPrefix = ARGV[1]
local userPrefix = ARGV[2]
local holdPrefix = ARGV[3]
local limit = ARGV[4]

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local pos = 5

-- Every seat of the event, in chunks: unpack() of thousands of values can overflow Lua's stack.
local seatCount = tonumber(ARGV[pos])
pos = pos + 1
local chunk = {}
for i = 1, seatCount do
    chunk[#chunk + 1] = ARGV[pos]
    pos = pos + 1
    if #chunk == 500 or i == seatCount then
        redis.call('SADD', KEYS[2], unpack(chunk))
        chunk = {}
    end
end

-- Sold seats: a marker with no expiry. Postgres is the truth at load time, so it overwrites.
local soldCount = tonumber(ARGV[pos])
pos = pos + 1
for _ = 1, soldCount do
    redis.call('SET', seatPrefix .. ARGV[pos], 'S:' .. ARGV[pos + 1] .. ':' .. ARGV[pos + 2])
    pos = pos + 3
end

-- Sold seats keep counting towards their buyer's cap forever (score +inf, D-005, D-022).
local touchedUsers = {}
local memberCount = tonumber(ARGV[pos])
pos = pos + 1
for _ = 1, memberCount do
    redis.call('ZADD', userPrefix .. ARGV[pos], '+inf', ARGV[pos + 1])
    touchedUsers[ARGV[pos]] = true
    pos = pos + 2
end

-- Unexpired ACTIVE holds, written exactly as hold.lua writes them. A hold whose time has already run
-- out by Redis' clock is skipped: its seats are free in Redis, and the sweeper closes it in Postgres.
local restored = 0
local skipped = 0
local holdCount = tonumber(ARGV[pos])
pos = pos + 1
for _ = 1, holdCount do
    local holdId = ARGV[pos]
    local userId = ARGV[pos + 1]
    local createdMs = ARGV[pos + 2]
    local expiresMs = ARGV[pos + 3]
    local seatList = ARGV[pos + 4]
    local n = ARGV[pos + 5]
    pos = pos + 6
    local expires = tonumber(expiresMs)
    if expires > now then
        for seatId in string.gmatch(seatList, '[^,]+') do
            redis.call('SET', seatPrefix .. seatId, 'H:' .. holdId .. ':' .. createdMs, 'PXAT', expires)
        end
        local holdKey = holdPrefix .. holdId
        redis.call('HSET', holdKey, 'user', userId, 'seats', seatList, 'n', n,
                'createdMs', createdMs, 'expiresMs', expiresMs)
        redis.call('PEXPIREAT', holdKey, expires)
        redis.call('ZADD', userPrefix .. userId, expires, holdId .. ':' .. n)
        touchedUsers[userId] = true
        restored = restored + 1
    else
        skipped = skipped + 1
    end
end

-- Each user key expires with its last member, as in hold.lua; a sold member never expires.
for userId, _ in pairs(touchedUsers) do
    local key = userPrefix .. userId
    local last = redis.call('ZRANGE', key, -1, -1, 'WITHSCORES')
    if last[2] == 'inf' then
        redis.call('PERSIST', key)
    else
        redis.call('PEXPIREAT', key, tonumber(last[2]))
    end
end

-- Written last: hold.lua treats meta's existence as "the event is completely loaded". loadedMs is the
-- load epoch: the reconciler compares it before closing holds, to notice a wipe and reload mid-pass.
local nowText = string.format('%.0f', now)
redis.call('HSET', KEYS[1], 'limit', limit, 'seatCount', tostring(seatCount), 'loadedMs', nowText)

return {'LOADED', tostring(restored), tostring(skipped), nowText}
