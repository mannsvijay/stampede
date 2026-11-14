-- Read-only snapshot of one event's Redis-first state, for invariant 5 (PRD 6.10, D-022 M2.2c-2).
-- Writes nothing. One script = one atomic read: Redis runs it as a single command, so every value
-- below is from the same instant (and expiry is judged at the script's start time).
--
-- KEYS[1] event meta hash     KEYS[2] event seats set     KEYS[3] the inventory stream
-- ARGV[1] mode: 'ALL' (the whole event) or 'KEYS' (only the ids listed below)
-- ARGV[2] event key prefix ('<p>:ev:<event>:')    ARGV[3] consumer group    ARGV[4] eventId
-- ARGV[5] entry window in ms: only unapplied stream entries younger than this are returned
-- 'KEYS' mode: ARGV[6] seat count, seat ids..., hold count, hold ids..., user count, user ids...
--
-- Reply: {'NOT_LOADED'} or
--   {'LOADED', nowMs, limit, {seatId...}, {seatId, value, ...},
--    {holdId, user, seats, createdMs, expiresMs, pttl, ...}, {userId, {member, score, ...}, ...},
--    {holdId, type, entryMs, ...}}

local metaKey, seatsKey, stream = KEYS[1], KEYS[2], KEYS[3]
local mode = ARGV[1]
local eventPrefix = ARGV[2]
local group = ARGV[3]
local eventId = ARGV[4]
local windowMs = tonumber(ARGV[5])

local limit = redis.call('HGET', metaKey, 'limit')
if not limit then
    return {'NOT_LOADED'}
end

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local seatIds, holdIds, userIds = {}, {}, {}
local seen = {}
local function add(list, kind, id)
    if not seen[kind .. id] then
        seen[kind .. id] = true
        list[#list + 1] = id
    end
end

if mode == 'ALL' then
    -- SCAN, not KEYS: same result, but it pages. Inside a script it still runs to the end in one go,
    -- so the script blocks Redis for one pass over the keyspace (measured in D-022).
    local cursor = '0'
    repeat
        local page = redis.call('SCAN', cursor, 'MATCH', eventPrefix .. '*', 'COUNT', 1000)
        cursor = page[1]
        for _, key in ipairs(page[2]) do
            local rest = string.sub(key, #eventPrefix + 1)
            local kind = string.sub(rest, 1, 5)
            if kind == 'seat:' then
                add(seatIds, 's', string.sub(rest, 6))
            elseif kind == 'hold:' then
                add(holdIds, 'h', string.sub(rest, 6))
            elseif kind == 'user:' then
                add(userIds, 'u', string.sub(rest, 6))
            end
        end
    until cursor == '0'
else
    local pos = 6
    for _, list in ipairs({{seatIds, 's'}, {holdIds, 'h'}, {userIds, 'u'}}) do
        local count = tonumber(ARGV[pos])
        for i = 1, count do
            add(list[1], list[2], ARGV[pos + i])
        end
        pos = pos + count + 1
    end
end

local seatValues = {}
-- MGET in chunks: one command per 500 seats instead of one per seat (each redis.call has a fixed cost).
for from = 1, #seatIds, 500 do
    local chunk = {}
    for i = from, math.min(from + 499, #seatIds) do
        chunk[#chunk + 1] = eventPrefix .. 'seat:' .. seatIds[i]
    end
    local values = redis.call('MGET', unpack(chunk))
    for i, value in ipairs(values) do
        if value then
            local seatId = seatIds[from + i - 1]
            seatValues[#seatValues + 1] = seatId
            seatValues[#seatValues + 1] = value
            -- A re-read must be able to interpret the value: read the hold it names too.
            local named = string.match(value, '^H:([^:]+):')
            if named then
                add(holdIds, 'h', named)
            end
        end
    end
end

local users = {}
for _, userId in ipairs(userIds) do
    local members = redis.call('ZRANGE', eventPrefix .. 'user:' .. userId, 0, -1, 'WITHSCORES')
    if #members > 0 then
        users[#users + 1] = userId
        users[#users + 1] = members
        for i = 1, #members, 2 do
            local named = string.match(members[i], '^(.*):%d+$')
            if named then
                add(holdIds, 'h', named)
            end
        end
    end
end

local holds = {}
for _, holdId in ipairs(holdIds) do
    local key = eventPrefix .. 'hold:' .. holdId
    local hold = redis.call('HMGET', key, 'user', 'seats', 'createdMs', 'expiresMs')
    if hold[1] then
        holds[#holds + 1] = holdId
        holds[#holds + 1] = hold[1]
        holds[#holds + 1] = hold[2]
        holds[#holds + 1] = hold[3]
        holds[#holds + 1] = hold[4]
        holds[#holds + 1] = tostring(redis.call('PTTL', key))   -- -1 = no expiry = pinned by a sale
    end
end

-- Stream entries of this event the group has not acknowledged yet: pending (delivered, not acked) and
-- not yet delivered (after the group's last-delivered id). Only those younger than the window.
local unapplied = {}
local minMs = now - windowMs
local function collect(entry)
    local id, fields = entry[1], entry[2]
    local entryMs = tonumber(string.match(id, '^(%d+)-'))
    if entryMs < minMs then
        return
    end
    local f = {}
    for i = 1, #fields, 2 do
        f[fields[i]] = fields[i + 1]
    end
    if f['event'] == eventId and f['hold'] then
        unapplied[#unapplied + 1] = f['hold']
        unapplied[#unapplied + 1] = f['type'] or '?'
        unapplied[#unapplied + 1] = string.format('%.0f', entryMs)
    end
end
if redis.call('EXISTS', stream) == 1 then
    local startId = string.format('%.0f', math.max(0, minMs)) .. '-0'
    local lastDelivered
    for _, info in ipairs(redis.call('XINFO', 'GROUPS', stream)) do
        local name, last
        for i = 1, #info, 2 do
            if info[i] == 'name' then
                name = info[i + 1]
            elseif info[i] == 'last-delivered-id' then
                last = info[i + 1]
            end
        end
        if name == group then
            lastDelivered = last
        end
    end
    if lastDelivered then
        for _, p in ipairs(redis.call('XPENDING', stream, group, startId, '+', 10000)) do
            local found = redis.call('XRANGE', stream, p[1], p[1])
            if found[1] then
                collect(found[1])
            end
        end
        for _, entry in ipairs(redis.call('XRANGE', stream, '(' .. lastDelivered, '+', 'COUNT', 10000)) do
            collect(entry)
        end
    else
        -- No group yet: nothing has been applied.
        for _, entry in ipairs(redis.call('XRANGE', stream, startId, '+', 'COUNT', 10000)) do
            collect(entry)
        end
    end
end

return {'LOADED', string.format('%.0f', now), limit, redis.call('SMEMBERS', seatsKey), seatValues, holds, users,
        unapplied}
