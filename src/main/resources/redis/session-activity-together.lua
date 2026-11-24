if #KEYS ~= 2 or KEYS[1] == KEYS[2] then
	return {0}
end

local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local idle = 1800000
local absolute = 28800000

for _, key in ipairs(KEYS) do
	local values = redis.call('HMGET', key, 'started', 'activity', 'mfa')
	local started = tonumber(values[1])
	local activity = tonumber(values[2])
	if not started or not activity or values[3] ~= '1'
			or started > activity or activity > now
			or now >= math.min(started + absolute, activity + idle) then
		return {0}
	end
end

return {1, now}
