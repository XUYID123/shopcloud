-- 调用方：网关 RbacPermissionCache.coldLoad，在 Feign 查询成功且用户身份/文档校验通过后执行。
-- KEYS[1]：epoch；KEYS[2]：用户权限 JSON；KEYS[3]：加载锁；三者使用同一用户哈希标签。
-- ARGV[1]：查询前记录的 epoch；ARGV[2]：新 JSON；ARGV[3]：TTL毫秒；ARGV[4]：Redisson clientId:逻辑归属ID。
-- 只有仍持有加载锁且期间没有失效，才可回填查询结果。
-- Lua 内连续完成比较与写入，避免在 Java 中先 GET 再 SET 之间插入一次失效。
-- Redisson 3.52.0 的普通 RLock 用 Hash 保存 clientId:threadId -> 重入计数。
-- 将 HEXISTS 与 epoch 检查/写入放在同一个脚本内，避免锁到期后旧查询仍回填。
if redis.call('GET', KEYS[1]) ~= ARGV[1] or redis.call('HEXISTS', KEYS[3], ARGV[4]) ~= 1 then
    -- 0 表示已经失去回填资格，网关不能继续使用这份旧结果授权本次请求。
    return 0
end
-- 带 TTL 保存文档；这里只写数据，锁由 usingWhen 的清理回调按所有者释放。
redis.call('PSETEX', KEYS[2], ARGV[3], ARGV[2])
-- 1 仅表示本次满足条件并写入，不表示后续权限永远不会变化或已完成数据库事务。
return 1
