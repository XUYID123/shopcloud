-- 入库授权与商品资料维护分开。公开注册用户只会获得原有 USER 角色。
INSERT INTO permission (permission_code, permission_name) VALUES ('inventory:write', '管理库存批次入库');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM `role` r CROSS JOIN permission p
WHERE r.role_code = 'ADMIN' AND p.permission_code = 'inventory:write';
