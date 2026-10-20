-- =====================================================================
-- 2026-10-20 商品分类表整理
--
-- 修复前问题：
--   1. 旧重复分类（100/105/110/115/119 及其子分类）仍以 deleted=1 留在表中；
--   2. 一级分类 sort 为 12/33/62/65/65/68/73/94，前端展示顺序混乱；
--   3. 一级分类 5 美妆个护、6 家居家装 与多个二级分类 status=1（隐藏），
--      但下面有商品，导致分类导航看不到这些商品；
--   4. 部分二级分类 sort 不连续。
--
-- 执行前备份：category_bak_20261020（见 CREATE TABLE 语句）。
-- 说明：空分类（平板电脑 / 水果 / 蔬菜 / 香水 / 教材 / 绘本）保留，
--       待有商品后再补充，不因暂时为空而删除。
-- =====================================================================

CREATE TABLE IF NOT EXISTS shop.category_bak_20261020 AS SELECT * FROM shop.category;

START TRANSACTION;

-- 1. 物理删除旧重复分类（已无商品引用）
DELETE FROM shop.category WHERE deleted = 1;

-- 2. 一级分类：统一排序、全部显示
UPDATE shop.category SET sort = 1, status = 0 WHERE id = 2;  -- 手机数码
UPDATE shop.category SET sort = 2, status = 0 WHERE id = 1;  -- 家用电器
UPDATE shop.category SET sort = 3, status = 0 WHERE id = 3;  -- 服装鞋包
UPDATE shop.category SET sort = 4, status = 0 WHERE id = 4;  -- 食品生鲜
UPDATE shop.category SET sort = 5, status = 0 WHERE id = 5;  -- 美妆个护
UPDATE shop.category SET sort = 6, status = 0 WHERE id = 6;  -- 家居家装
UPDATE shop.category SET sort = 7, status = 0 WHERE id = 7;  -- 母婴玩具
UPDATE shop.category SET sort = 8, status = 0 WHERE id = 8;  -- 图书文娱

-- 3. 二级分类：各一级分类下重新编号、全部显示
-- 手机数码
UPDATE shop.category SET sort = 1, status = 0 WHERE id = 13;
UPDATE shop.category SET sort = 2, status = 0 WHERE id = 14;
UPDATE shop.category SET sort = 3, status = 0 WHERE id = 15;
UPDATE shop.category SET sort = 4, status = 0 WHERE id = 16;
UPDATE shop.category SET sort = 5, status = 0 WHERE id = 203;
UPDATE shop.category SET sort = 6, status = 0 WHERE id = 204;
-- 家用电器
UPDATE shop.category SET sort = 1, status = 0 WHERE id = 9;
UPDATE shop.category SET sort = 2, status = 0 WHERE id = 10;
UPDATE shop.category SET sort = 3, status = 0 WHERE id = 11;
UPDATE shop.category SET sort = 4, status = 0 WHERE id = 12;
UPDATE shop.category SET sort = 5, status = 0 WHERE id = 209;
-- 服装鞋包
UPDATE shop.category SET sort = 1, status = 0 WHERE id = 17;
UPDATE shop.category SET sort = 2, status = 0 WHERE id = 18;
UPDATE shop.category SET sort = 3, status = 0 WHERE id = 19;
UPDATE shop.category SET sort = 4, status = 0 WHERE id = 20;
UPDATE shop.category SET sort = 5, status = 0 WHERE id = 208;
-- 食品生鲜
UPDATE shop.category SET sort = 1, status = 0 WHERE id = 21;
UPDATE shop.category SET sort = 2, status = 0 WHERE id = 22;
UPDATE shop.category SET sort = 3, status = 0 WHERE id = 23;
UPDATE shop.category SET sort = 4, status = 0 WHERE id = 24;
UPDATE shop.category SET sort = 5, status = 0 WHERE id = 205;
UPDATE shop.category SET sort = 6, status = 0 WHERE id = 206;
UPDATE shop.category SET sort = 7, status = 0 WHERE id = 207;
-- 美妆个护
UPDATE shop.category SET sort = 1, status = 0 WHERE id = 25;
UPDATE shop.category SET sort = 2, status = 0 WHERE id = 26;
UPDATE shop.category SET sort = 3, status = 0 WHERE id = 27;
UPDATE shop.category SET sort = 4, status = 0 WHERE id = 210;
-- 家居家装
UPDATE shop.category SET sort = 1, status = 0 WHERE id = 28;
UPDATE shop.category SET sort = 2, status = 0 WHERE id = 29;
UPDATE shop.category SET sort = 3, status = 0 WHERE id = 30;
-- 母婴玩具
UPDATE shop.category SET sort = 1, status = 0 WHERE id = 201;
UPDATE shop.category SET sort = 2, status = 0 WHERE id = 202;
-- 图书文娱
UPDATE shop.category SET sort = 1, status = 0 WHERE id = 120;
UPDATE shop.category SET sort = 2, status = 0 WHERE id = 121;
UPDATE shop.category SET sort = 3, status = 0 WHERE id = 122;
UPDATE shop.category SET sort = 4, status = 0 WHERE id = 123;

COMMIT;

-- 校验（期望：一级 8 个、logical_deleted=0、二级 36 个）
-- SELECT COUNT(*) FROM shop.category WHERE parent_id = 0;
-- SELECT COUNT(*) FROM shop.category WHERE deleted = 1;
-- SELECT COUNT(*) FROM shop.category WHERE parent_id <> 0;
