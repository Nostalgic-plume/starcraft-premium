-- 商品收藏表（2026-10-22）
-- 设计说明：
--   1. 取消收藏 = 物理删除，不设 deleted 字段（收藏是可随时重建的弱数据，无需留痕）
--   2. (user_id, product_id) 唯一索引：天然保证同一用户对同一商品只有一条收藏记录，
--      重复收藏走 INSERT IGNORE / 先查后插，不会产生脏数据
--   3. 不存商品快照：收藏列表实时回查商品表，商品下架/删除后自动不展示（与订单快照的取舍相反）

CREATE TABLE IF NOT EXISTS `favorite` (
  `id`          BIGINT   NOT NULL COMMENT '主键（雪花 ID）',
  `user_id`     BIGINT   NOT NULL COMMENT '用户 ID',
  `product_id`  BIGINT   NOT NULL COMMENT '商品 ID',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_product` (`user_id`, `product_id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品收藏';
