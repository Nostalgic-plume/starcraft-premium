-- 品牌主数据迁移：product.brand（品牌名）→ product.brand_id（品牌ID）+ 新建 brand 表
-- 执行库：shop  执行方式：mysql -u root -D shop < migrate-brand-20260927.sql
-- 说明：执行前请先跑 backup-product-20260927.sql 同目录下的备份命令

SET NAMES utf8mb4;
SET @db := 'shop';

-- ========== 1. 品牌主数据表 ==========
CREATE TABLE IF NOT EXISTS brand (
    id          bigint       NOT NULL                        COMMENT '品牌ID',
    name        varchar(100) NOT NULL                        COMMENT '品牌名',
    logo        varchar(500)          DEFAULT NULL           COMMENT '品牌LOGO',
    description varchar(500)          DEFAULT NULL           COMMENT '品牌简介',
    sort        int          NOT NULL DEFAULT 0              COMMENT '排序（升序）',
    status      tinyint      NOT NULL DEFAULT 0              COMMENT '状态：0正常 1停用',
    create_time datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time datetime              DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     tinyint      NOT NULL DEFAULT 0              COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name),
    KEY idx_status_sort (status, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品牌主数据';

-- ========== 2. 用商品表现有品牌名回填品牌表（按品牌名升序分配 ID）==========
INSERT INTO brand (id, name, status, sort)
SELECT ROW_NUMBER() OVER (ORDER BY t.brand) AS id, t.brand, 0, 0
FROM (SELECT DISTINCT brand FROM product WHERE brand IS NOT NULL AND brand <> '') t;

-- ========== 3. 商品表新增品牌ID并回填 ==========
ALTER TABLE product
    ADD COLUMN brand_id bigint NULL COMMENT '品牌ID（关联 brand.id）' AFTER first_category_id;

UPDATE product p
JOIN brand b ON p.brand = b.name
SET p.brand_id = b.id;

-- ========== 4. 迁移校验（应在执行删除前列全为通过）==========
SELECT (SELECT COUNT(*) FROM product)                                              AS 商品总数,
       (SELECT COUNT(*) FROM brand)                                                AS 品牌总数,
       (SELECT COUNT(*) FROM product WHERE brand_id IS NOT NULL)                    AS 已回填商品数,
       (SELECT COUNT(*) FROM product p LEFT JOIN brand b ON p.brand_id = b.id
         WHERE b.id IS NULL)                                                       AS 悬空品牌ID,
       (SELECT COUNT(*) FROM product WHERE brand IS NOT NULL AND brand <> ''
         AND brand_id IS NULL)                                                     AS 未回填品牌名;

-- ========== 5. 删除旧品牌名列、补品牌ID索引 ==========
-- 说明：brand 列被删除后，原 FULLTEXT 索引 ft_product_search 自动变为 (name, description)
ALTER TABLE product
    DROP COLUMN brand,
    ADD KEY idx_brand_id (brand_id);

-- ========== 6. 迁移后结构核对 ==========
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME IN ('product', 'brand')
ORDER BY TABLE_NAME, ORDINAL_POSITION;

SELECT INDEX_NAME, INDEX_TYPE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS 列
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'product'
GROUP BY INDEX_NAME, INDEX_TYPE;
