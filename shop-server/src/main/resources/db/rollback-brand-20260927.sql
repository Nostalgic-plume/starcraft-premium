-- 回滚脚本：撤销品牌主数据迁移，恢复 product.brand（品牌名）与旧全文索引
-- 前提：product.brand_id 与 brand 表仍存在

SET NAMES utf8mb4;

-- 1. 恢复品牌名列并用品牌表回填
ALTER TABLE product
    ADD COLUMN brand varchar(100) NULL COMMENT '品牌' AFTER first_category_id;

UPDATE product p
JOIN brand b ON p.brand_id = b.id
SET p.brand = b.name;

-- 2. 恢复旧全文索引（含 brand 列）
ALTER TABLE product DROP INDEX ft_product_search;
ALTER TABLE product ADD FULLTEXT KEY ft_product_search (name, description, brand);

-- 3. 删除品牌ID列与品牌表
ALTER TABLE product DROP INDEX idx_brand_id;
ALTER TABLE product DROP COLUMN brand_id;
DROP TABLE IF EXISTS brand;

-- 4. 回滚核对
SELECT COUNT(*) AS 商品总数,
       SUM(CASE WHEN brand IS NULL OR brand = '' THEN 1 ELSE 0 END) AS 无品牌商品
FROM product;
