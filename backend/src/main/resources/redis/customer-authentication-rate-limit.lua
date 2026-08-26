local limits = {
    tonumber(ARGV[1]),
    tonumber(ARGV[2]),
    tonumber(ARGV[3])
}
local window_millis = tonumber(ARGV[4])
local denied = false
local retry_after_millis = 0

for index = 1, 3 do
    local current = tonumber(redis.call('GET', KEYS[index]) or '0')
    if current >= limits[index] then
        denied = true
        local ttl = redis.call('PTTL', KEYS[index])
        if ttl < 1 then
            ttl = window_millis
        end
        if ttl > retry_after_millis then
            retry_after_millis = ttl
        end
    end
end

if denied then
    return 'DENY:' .. retry_after_millis
end

for index = 1, 3 do
    local current = redis.call('INCR', KEYS[index])
    if current == 1 then
        redis.call('PEXPIRE', KEYS[index], window_millis)
    end
end

return 'ALLOW:0'
