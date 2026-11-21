local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local idle = 1800000
local absolute = 28800000
if ARGV[1] == 'end' then
	redis.call('DEL', KEYS[1])
	return {1}
end
if ARGV[1] == 'start' then
	redis.call('HSET', KEYS[1], 'started', now, 'activity', now, 'mfa', ARGV[2])
	redis.call('PEXPIRE', KEYS[1], idle)
	return {1}
end
local values = redis.call('HMGET', KEYS[1], 'started', 'activity', 'mfa')
if not values[1] or not values[2] then
	return {0}
end
local started = tonumber(values[1])
local activity = tonumber(values[2])
local deadline = math.min(started + absolute, activity + idle)
if now >= deadline then
	redis.call('DEL', KEYS[1])
	return {0}
end
if ARGV[1] == 'touch' then
	local request = tonumber(ARGV[2])
	if request < deadline and request <= now and request > activity then
		activity = request
		redis.call('HSET', KEYS[1], 'activity', activity)
		redis.call('PEXPIRE', KEYS[1], math.max(1, math.min(started + absolute, activity + idle) - now))
	end
end
return {1, started, activity, tonumber(values[3])}
