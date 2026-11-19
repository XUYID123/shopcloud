-- 调用方：shop-user 的 PermissionCacheInvalidator，在数据库提交后或补偿任务中执行。
-- KEYS[1]：用户 epoch；KEYS[2]：用户 JSON；ARGV[1]：本次失效的新 UUID。
-- 先更换代号，再删除数据；同一脚本原子执行，旧加载结果不能跨越失效重新写回。
-- 即使缓存原本不存在，也要更换代号，让“已经开始但尚未回填”的查询失去资格。
redis.call('SET', KEYS[1], ARGV[1])
-- 返回 DEL 数量：0/1 都是正常失效结果，不存在不等于 Redis 故障。
-- 本脚本不撤回已经完成的鉴权，也不与 MySQL 构成跨存储原子事务。
return redis.call('DEL', KEYS[2])
