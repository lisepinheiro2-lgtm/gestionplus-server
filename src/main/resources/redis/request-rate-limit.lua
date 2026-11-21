local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local capacity = tonumber(ARGV[4])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now - window)
local count = redis.call('ZCARD', KEYS[2])
if count >= limit then
	local oldest = redis.call('ZRANGE', KEYS[2], 0, 0, 'WITHSCORES')
	return {0, math.max(1, tonumber(oldest[2]) + window - now)}
end
if count == 0 and redis.call('ZCARD', KEYS[1]) >= capacity then
	return {-1, 0}
end
redis.call('ZADD', KEYS[2], now, ARGV[3])
redis.call('PEXPIRE', KEYS[2], window)
redis.call('ZADD', KEYS[1], now + window, KEYS[2])
redis.call('PEXPIRE', KEYS[1], window)
return {1, 0}
