-- 单个“策略 + IP”的原子检查：滑动窗口、违规计数、临时封禁在同一次脚本执行中完成。
-- KEYS[1]：已放行请求的 ZSET，成员为请求 UUID，分数为 Redis 当前时间（毫秒）。
-- KEYS[2]：超额违规计数；KEYS[3]：临时封禁标记。三个键须在同一个 Cluster 槽位。
-- ARGV[1]：窗口毫秒；ARGV[2]：额度；ARGV[3]：请求 UUID；
-- ARGV[4]：触发封禁的违规次数；ARGV[5]：封禁毫秒。
-- 返回 {是否放行（1/0）, 建议等待毫秒, 是否封禁（1/0）}。
-- 调用方 RedisAbuseLimiter 明确连接 6380；本脚本不读取 6381 权限数据，也不认证 JWT。
-- ARGV[3] 是本次策略记录的事件 UUID，不是整次 HTTP 请求关联用的 X-Request-Id。
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local banAfter = tonumber(ARGV[4])
local banDuration = tonumber(ARGV[5])
-- 优先检查封禁；已有封禁直接返回剩余时间，不因再次访问而续期。
local banTtl = redis.call('PTTL', KEYS[3])
if banTtl > 0 then
    return {0, banTtl, 1}
end
-- 使用 Redis 的统一时钟，避免不同网关实例的系统时间偏差影响窗口。
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
-- 清除窗口左边界及更早的记录，剩余成员才占用当前滑动窗口额度。
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
if redis.call('ZCARD', KEYS[1]) >= limit then
    -- 超额请求不加入事件集合，只累计违规；首次违规设置过期时间，后续违规不续期。
    local violations = redis.call('INCR', KEYS[2])
    if violations == 1 then
        redis.call('PEXPIRE', KEYS[2], window)
    end
    if violations >= banAfter then
        -- 达到阈值后创建带 TTL 的封禁标记，并清除这一轮违规次数。
        redis.call('SET', KEYS[3], '1', 'PX', banDuration)
        redis.call('DEL', KEYS[2])
        return {0, banDuration, 1}
    end
    -- 最早一条已放行记录退出窗口时才会空出额度，据此计算 Retry-After。
    local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
    return {0, math.max(1, tonumber(oldest[2]) + window - now), 0}
end
-- 有额度才记录事件；UUID 避免同一毫秒的并发请求覆盖同一个成员。
redis.call('ZADD', KEYS[1], now, ARGV[3])
-- 每次放行刷新事件集合 TTL，使最后一条事件也保留完整窗口，无访问后可自动回收。
redis.call('PEXPIRE', KEYS[1], window)
-- 放行会清空对应策略的违规计数，因此封禁累计受成功访问和违规计数 TTL 共同约束。
redis.call('DEL', KEYS[2])
return {1, 0, 0}
