local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local window = 900000
local restriction = 300000
local lease = 120000
local capacity = 8192
local accountID = ARGV[2]
local addressID = ARGV[3]

local function expireEntries(values, expirations, unknown)
	local expired = redis.call('ZRANGEBYSCORE', expirations, '-inf', now)
	for _, id in ipairs(expired) do
		redis.call('HDEL', values, id)
		redis.call('ZREM', expirations, id)
		if unknown then
			redis.call('ZREM', unknown, id)
		end
	end
end

local function recentFailures(history)
	local recent = {}
	for _, timestamp in ipairs(history.failures or {}) do
		if timestamp > now - window then
			table.insert(recent, timestamp)
		end
	end
	history.failures = recent
end

local function saveAccount(account)
	local expiration = math.max(account.blockedUntil or 0, account.leaseUntil or 0)
	if #account.failures > 0 then
		expiration = math.max(expiration, account.failures[#account.failures] + window)
	end
	if expiration <= now then
		redis.call('HDEL', KEYS[1], accountID)
		redis.call('ZREM', KEYS[2], accountID)
		redis.call('ZREM', KEYS[3], accountID)
		return
	end
	redis.call('HSET', KEYS[1], accountID, cjson.encode(account))
	redis.call('ZADD', KEYS[2], expiration, accountID)
	if not account.known and not account.reservationID then
		redis.call('ZADD', KEYS[3], expiration, accountID)
	else
		redis.call('ZREM', KEYS[3], accountID)
	end
end

expireEntries(KEYS[1], KEYS[2], KEYS[3])
expireEntries(KEYS[4], KEYS[5], nil)
local encodedAccount = redis.call('HGET', KEYS[1], accountID)
local account = encodedAccount and cjson.decode(encodedAccount) or {failures = {}, known = false}
recentFailures(account)

if ARGV[1] == 'begin' then
	if (account.reservationID and (account.leaseUntil or 0) > now)
		or (account.blockedUntil or 0) > now then
		return {0}
	end
	if not encodedAccount and redis.call('HLEN', KEYS[1]) >= capacity then
		local candidate = redis.call('ZRANGE', KEYS[3], 0, 0)
		if #candidate == 0 then
			return {0}
		end
		redis.call('HDEL', KEYS[1], candidate[1])
		redis.call('ZREM', KEYS[2], candidate[1])
		redis.call('ZREM', KEYS[3], candidate[1])
	end
	local encodedAddress = redis.call('HGET', KEYS[4], addressID)
	local address = encodedAddress and cjson.decode(encodedAddress) or {failures = {}}
	recentFailures(address)
	if not encodedAddress and redis.call('HLEN', KEYS[4]) >= capacity and not account.known then
		local last = tonumber(redis.call('GET', KEYS[6]) or '-5000')
		if now < last + 5000 then
			return {0}
		end
		redis.call('SET', KEYS[6], now, 'PX', window)
	end
	local lastAccount = account.failures[#account.failures] or 0
	local lastAddress = address.failures[#address.failures] or 0
	local delay = math.min(2000, 200 * #account.failures + 500 * math.floor(#address.failures / 10))
	if now < math.max(lastAccount, lastAddress) + delay then
		return {0}
	end
	account.reservationID = ARGV[4]
	account.leaseUntil = now + lease
	saveAccount(account)
	for index = 1, 5 do
		redis.call('PEXPIRE', KEYS[index], window)
	end
	return {1}
end

if not encodedAccount or account.reservationID ~= ARGV[4] or (account.leaseUntil or 0) <= now then
	return {-1}
end
account.reservationID = nil
account.leaseUntil = nil
local restricted = false
if ARGV[5] == '1' then
	account.known = account.known or ARGV[6] == '1'
	restricted = #account.failures >= 9
	if #account.failures >= 10 then
		table.remove(account.failures, 1)
	end
	table.insert(account.failures, now)
	if restricted then
		account.blockedUntil = now + restriction
	end
	local encodedAddress = redis.call('HGET', KEYS[4], addressID)
	if encodedAddress or redis.call('HLEN', KEYS[4]) < capacity then
		local address = encodedAddress and cjson.decode(encodedAddress) or {failures = {}}
		recentFailures(address)
		if #address.failures >= 30 then
			table.remove(address.failures, 1)
		end
		table.insert(address.failures, now)
		redis.call('HSET', KEYS[4], addressID, cjson.encode(address))
		redis.call('ZADD', KEYS[5], now + window, addressID)
	end
end
saveAccount(account)
for index = 1, 5 do
	redis.call('PEXPIRE', KEYS[index], window)
end
return {restricted and 1 or 0}
