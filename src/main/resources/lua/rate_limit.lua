-- 限流：滑动窗口（ZSET）
-- KEYS[1] = 限流 key
-- ARGV[1] = 当前时间戳(毫秒)   ARGV[2] = 阈值   ARGV[3] = 窗口大小(毫秒)
-- 返回 {是否放行(1/0), 剩余额度, 窗口重置时间(毫秒)}
local now    = tonumber(ARGV[1])
local limit  = tonumber(ARGV[2])
local window = tonumber(ARGV[3])

-- 清掉窗口外的旧记录（score = 请求时间）
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)

-- 数窗口内的请求数
local count = redis.call('ZCARD', KEYS[1])

-- 超限
if count >= limit then
    return {0, 0, now + window}
end

-- 放行：记录本次请求
-- ★ member 必须唯一，不能直接用 now：
--   ZSET 的 member 重复时是【覆盖】而不是新增，同一毫秒内的多个请求会被合并成一条，
--   导致计数偏少、限流形同虚设（阈值 5 时打 8 次都可能不触发）。
--   所以 member 用 "时间戳-随机数"，score 仍然是纯时间戳（用于按窗口清理）。
local member = now .. '-' .. math.random(1000000)
redis.call('ZADD', KEYS[1], now, member)
redis.call('PEXPIRE', KEYS[1], window)   -- ★ 必须续期，否则冷 key 永久残留

-- 窗口重置时间：最早一条记录 + 窗口大小
local earliest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
local resetAt = now + window
if earliest[2] then
    resetAt = tonumber(earliest[2]) + window
end

-- 本次放行后还剩多少额度（已包含本次）
return {1, limit - count - 1, resetAt}
