-- 调用方：网关 RbacPermissionCache.coldLoad；仅在取得用户加载锁后调用。
-- KEYS[1]：该用户的 epoch Key；ARGV[1]：调用方新生成的 UUID，供 Key 不存在时使用。
-- 返回当前非空代号。它与请求 traceId、文档 version、锁 owner 互不相同。
-- 每次冷加载取得非空、随机的代号；键被淘汰或 Redis 重启后不会再次把空值当作同一代。
-- 此代号不设置主动 TTL，但业务 Redis 仍可淘汰它；回填时发现丢失/变化必须失败。
local epoch = redis.call('GET', KEYS[1])
-- 已存在时复用当前代号；不存在才初始化，不能每次读取都改变其他在途请求的代号。
if not epoch then
    epoch = ARGV[1]
    redis.call('SET', KEYS[1], epoch)
end
-- 网关记录该值，Feign 返回后交给回填脚本再次比较。
return epoch
