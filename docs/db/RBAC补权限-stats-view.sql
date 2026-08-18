-- =====================================================
-- RBAC 补权限：admin 角色 数据看板 stats:view（M3 需要）
-- 适用：数据库已有 RBAC 数据（RbacInitializer 旧版按「admin 角色存在即跳过」），
--       后续新增权限（stats:view）无法自动同步时的存量库手动兜底。
-- 幂等：permission.code 唯一 + role_permission 唯一键 (role_id, permission_id)，
--       重复执行安全（NOT EXISTS 守卫）。
-- =====================================================

-- ① 插入权限 stats:view（id=206，对应 PermissionConstants.STATS_VIEW）
--    注：permission.id 无 AUTO_INCREMENT，显式指定 id=206（现行为 200~205，206 空闲）
INSERT INTO permission (id, name, code, parent_id, sort, status, deleted)
SELECT 206, '查看统计', 'stats:view', 0, 0, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code = 'stats:view');

-- ② admin 角色（role_id=100）关联该权限
--    注：role_permission.id 无 AUTO_INCREMENT，取当前 MAX(id)+1 显式指定，避免与现存 id 冲突
INSERT INTO role_permission (id, role_id, permission_id)
SELECT (SELECT COALESCE(MAX(id), 0) + 1 FROM role_permission), 100, 206
WHERE NOT EXISTS (SELECT 1 FROM role_permission WHERE role_id = 100 AND permission_id = 206);
