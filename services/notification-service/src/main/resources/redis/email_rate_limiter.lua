local capacity  = tonumber(ARGV[1])
local windowMs  = tonumber(ARGV[2])
local now       = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])
local leakPerMs = capacity / windowMs

local data  = redis.call('HMGET', KEYS[1], 'level', 'ts')
local level = tonumber(data[1])
local ts    = tonumber(data[2])
if level == nil then
    level = 0
    ts = now
end

local elapsed = now - ts
if elapsed < 0 then elapsed = 0 end
level = level - elapsed * leakPerMs
if level < 0 then level = 0 end

local allowed = 0
if level + requested <= capacity then
    level = level + requested
    allowed = 1
end

redis.call('HSET', KEYS[1], 'level', level, 'ts', now)
redis.call('PEXPIRE', KEYS[1], windowMs)   -- простаивающий ключ сам исчезнет
return allowed