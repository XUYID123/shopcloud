INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM `role` r CROSS JOIN permission p
WHERE r.role_code='ADMIN' AND p.permission_code='order:read:own'
  AND NOT EXISTS (
      SELECT 1 FROM role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id
  );
