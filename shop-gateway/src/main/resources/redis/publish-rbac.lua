-- KEYS 为文档及可选用户 epoch；ARGV 为预期旧版本、新文档、新版本、TTL毫秒、新epoch。
-- 不发布消息；所有网关直接读 Redis。此脚本用于规则及受控开发数据写入。
-- 调用方：RbacRedisPublisher 与 Publish-DevRbac.ps1；正式用户权限通常走 Feign 冷加载。
-- KEYS[1] 为文档，存在 KEYS[2] 时它是用户 epoch；ARGV[1..5] 的顺序与 Java/脚本必须一致。
local previous = redis.call('GET', KEYS[1])
-- 完全相同的文档重试视为成功，不再次修改数据/epoch，也不会刷新原 TTL。
if previous == ARGV[2] then
    return 1
end
if previous then
    -- pcall 捕获 JSON 解码错误，返回负数让上层拒绝写入，不覆盖无法解释的原数据。
    local ok, document = pcall(cjson.decode, previous)
    if not ok then return -3 end
    -- CAS：当前版本与管理端读取的旧版本不同，说明期间已有别的写入。
    if document.version ~= ARGV[1] then return -1 end
    -- 同一个版本不允许承载不同内容，避免版本标识失去意义。
    if document.version == ARGV[3] then return -2 end
elseif ARGV[1] ~= '' then
    -- 管理端预期存在旧版本，但 Redis 已没有文档，不能当作普通覆盖继续执行。
    return -1
end
-- 所有条件通过后写入；TTL=0 的接口规则保持持久 Key，用户开发文档使用有限 TTL。
redis.call('SET', KEYS[1], ARGV[2])
if tonumber(ARGV[4]) > 0 then redis.call('PEXPIRE', KEYS[1], ARGV[4]) end
-- 主动写用户文档时还要更换 epoch，阻止先前 Feign 查询随后覆盖这次写入。
if #KEYS > 1 then redis.call('SET', KEYS[2], ARGV[5]) end
-- 1 代表文档写入成功，不是 Pub/Sub 订阅者数量。
return 1
