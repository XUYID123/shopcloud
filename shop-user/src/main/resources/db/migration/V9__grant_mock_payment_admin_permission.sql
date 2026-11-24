INSERT INTO permission (permission_code,permission_name) VALUES ('payment:mock:admin','管理模拟支付事件');
INSERT INTO role_permission (role_id,permission_id)
SELECT r.id,p.id FROM `role` r CROSS JOIN permission p
WHERE r.role_code='ADMIN' AND p.permission_code='payment:mock:admin';
