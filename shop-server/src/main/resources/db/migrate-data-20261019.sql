-- =====================================================================
-- 2026-10-19 商品数据质量修复
--
-- 背景（修复前实测）：
--   1. 285 个商品 category_id 直接指向一级分类（违反「必须挂二级分类」校验）；
--   2. 71 个商品关联已逻辑删除的分类（分类 7 母婴玩具、12 洗衣机被误删）；
--   3. 567 个商品关联已逻辑删除的品牌（8 个品牌被误删）；
--   4. 分类树存在重复一级分类（100/105/110/115/119 与 2/3/4/6/8 重复），
--      且分类 7 母婴玩具、8 图书文娱 下没有二级分类。
--
-- 修复原则：只改数据，不为脏数据改业务代码。
--
-- 执行前已备份：
--   product_bak_20261019 / category_bak_20261019 / brand_bak_20261019（首次修改前）
--   product_bak2_20261019 / category_bak2_20261019（结构修复前）
-- =====================================================================

-- 1. 恢复被误删的分类与品牌（均有商品引用）
UPDATE shop.category SET deleted = 0 WHERE id IN (7, 12);
UPDATE shop.brand    SET deleted = 0 WHERE id IN (1, 4, 5, 6, 7, 9, 11, 14);

-- 2. 合并重复一级分类：
--    119 图书文具 的二级分类迁移到 8 图书文娱 下；
--    其余重复一级（100/105/110/115/119）及其子分类无商品引用，逻辑删除。
UPDATE shop.category SET parent_id = 8 WHERE parent_id = 119;
UPDATE shop.category SET deleted = 1 WHERE id IN (100, 105, 110, 115, 119);
UPDATE shop.category SET deleted = 1 WHERE parent_id IN (100, 105, 110, 115);

-- 3. 补齐缺失的二级分类（分类 7 母婴玩具原先没有任何子分类）
INSERT INTO shop.category (id, name, parent_id, sort, status)
VALUES (201, '玩具', 7, 1, 0),
       (202, '母婴用品', 7, 2, 0);

-- 4. 将挂在「一级分类」上的商品归入该一级分类下排序第一的二级分类
--    （语义准确性受 mock 商品名与分类体系不匹配限制，结构一致性优先）
UPDATE shop.product p
    JOIN shop.category parent
    ON p.category_id = parent.id AND parent.parent_id = 0 AND parent.deleted = 0
SET p.category_id       = (SELECT c.id
                           FROM shop.category c
                           WHERE c.parent_id = parent.id AND c.deleted = 0
                           ORDER BY c.sort, c.id
                           LIMIT 1),
    p.first_category_id = parent.id
WHERE p.deleted = 0;

-- 5. 置空指向不存在品牌的 brand_id（品牌为可选字段）
UPDATE shop.product p
    LEFT JOIN shop.brand b ON p.brand_id = b.id AND b.deleted = 0
SET p.brand_id = NULL
WHERE p.deleted = 0 AND p.brand_id IS NOT NULL AND b.id IS NULL;

-- 6. 修复后校验（期望全部为 0）
-- SELECT '挂一级', COUNT(*) FROM shop.product p JOIN shop.category c ON p.category_id = c.id
--   WHERE p.deleted = 0 AND c.parent_id = 0;
-- SELECT '分类缺失', COUNT(*) FROM shop.product p LEFT JOIN shop.category c
--   ON p.category_id = c.id AND c.deleted = 0 WHERE p.deleted = 0 AND c.id IS NULL;
-- SELECT '品牌缺失', COUNT(*) FROM shop.product p LEFT JOIN shop.brand b
--   ON p.brand_id = b.id AND b.deleted = 0
--   WHERE p.deleted = 0 AND p.brand_id IS NOT NULL AND b.id IS NULL;
-- SELECT '重复一级', COUNT(*) FROM shop.category
--   WHERE parent_id = 0 AND deleted = 0 AND id > 100;

-- 7. 修复后必须重建索引：
--    商城 RAG：POST /api/admin/rag/rebuild（管理员登录）
--    推荐服务：重启进程（启动时自动全量重建状态缓存），或 POST /api/rec/items/rebuild
