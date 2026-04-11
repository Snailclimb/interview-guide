-- 单维度限流脚本
-- 基于滑动时间窗口的单维度原子限流
-- 由切面逐条调用实现多维度限流

-- 参数说明：
-- KEYS[1]: 限流维度键
-- ARGV[1]: 当前时间戳（毫秒）
-- ARGV[2]: 申请令牌数
-- ARGV[3]: 时间窗口（毫秒）
-- ARGV[4]: 最大令牌数（窗口内允许的总数）
-- ARGV[5]: 请求唯一标识

local key = KEYS[1]
local now_ms = tonumber(ARGV[1])
local permits = tonumber(ARGV[2])
local interval = tonumber(ARGV[3])
local max_tokens = tonumber(ARGV[4])
local request_id = ARGV[5]

local value_key = key .. ":value"
local permits_key = key .. ":permits"

-- 获取当前可用令牌（不存在则使用 max_tokens）
local current_val = tonumber(redis.call("get", value_key)) or max_tokens

-- 回收过期令牌
local expired_values = redis.call("zrangebyscore", permits_key, 0, now_ms - interval)
if #expired_values > 0 then
    local expired_count = 0
    for _, v in ipairs(expired_values) do
        local p = tonumber(string.match(v, ":(%d+)$"))
        if p then
            expired_count = expired_count + p
        end
    end

    redis.call("zremrangebyscore", permits_key, 0, now_ms - interval)

    if expired_count > 0 then
        current_val = math.min(max_tokens, current_val + expired_count)
    end
end

-- 检查可用令牌
if current_val < permits then
    return 0
end

-- 扣减令牌
local permit_record = request_id .. ":" .. permits
redis.call("zadd", permits_key, now_ms, permit_record)
redis.call("set", value_key, current_val - permits)

-- 设置过期时间（窗口的2倍，至少1秒）
local expire_time = math.ceil(interval * 2 / 1000)
if expire_time < 1 then expire_time = 1 end
redis.call("expire", value_key, expire_time)
redis.call("expire", permits_key, expire_time)

return 1
