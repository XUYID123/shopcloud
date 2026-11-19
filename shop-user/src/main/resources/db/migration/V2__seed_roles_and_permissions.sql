-- No administrator account or default user password is created here.
INSERT INTO `role` (role_code, role_name, remark) VALUES
    ('USER', '普通用户', '公开注册默认分配的角色'),
    ('ADMIN', '商城管理员', '仅通过受控管理流程分配');

INSERT INTO permission (permission_code, permission_name) VALUES
    ('cart:write:own', '管理自己的购物车'),
    ('order:create', '创建订单'),
    ('order:read:own', '查询自己的订单'),
    ('address:write:own', '管理自己的收货地址'),
    ('product:write', '管理商品及上下架'),
    ('order:read:any', '后台查询订单'),
    ('order:ship', '订单发货');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM `role` r
CROSS JOIN permission p
WHERE r.role_code = 'USER'
  AND p.permission_code IN (
      'cart:write:own', 'order:create', 'order:read:own', 'address:write:own'
  );

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM `role` r
CROSS JOIN permission p
WHERE r.role_code = 'ADMIN'
  AND p.permission_code IN ('product:write', 'order:read:any', 'order:ship');
