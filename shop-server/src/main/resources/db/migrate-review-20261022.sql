-- 商品评价表（2026-10-22）
-- 设计说明：
--   1. 一个订单项只能评价一次：uk_order_item 唯一索引兜底（比应用层查重更可靠）
--   2. 存商品名 / 主图快照：评价是历史行为，商品后续改名不应影响已发表评价的展示
--   3. images 用 JSON 字符串存图片 URL 数组（与 product.images 的存法一致）
--   4. 商家回复放在同一行（reply / reply_time）：一条订单项只有一条评价，无需单独回复表
--   5. 管理端删除走**物理删除**（见 ReviewMapper.hardDeleteById）：逻辑删除会继续占用
--      uk_order_item，导致该订单项永远无法再次评价

CREATE TABLE IF NOT EXISTS `review` (
  `id`            BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
  `user_id`       BIGINT       NOT NULL COMMENT '评价用户 ID',
  `order_id`      BIGINT       NOT NULL COMMENT '订单 ID',
  `order_item_id` BIGINT       NOT NULL COMMENT '订单项 ID（一个订单项只能评价一次）',
  `product_id`    BIGINT       NOT NULL COMMENT '商品 ID',
  `sku_id`        BIGINT       NULL COMMENT 'SKU ID',
  `product_name`  VARCHAR(200) NOT NULL COMMENT '商品名快照',
  `product_image` VARCHAR(500) NULL COMMENT '商品主图快照',
  `rating`        TINYINT      NOT NULL COMMENT '评分 1-5',
  `content`       VARCHAR(500) NULL COMMENT '评价内容',
  `images`        VARCHAR(2000) NULL COMMENT '晒图 URL 数组（JSON 字符串）',
  `is_anonymous`  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否匿名：0否 1是',
  `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0隐藏 1显示',
  `reply`         VARCHAR(500) NULL COMMENT '商家回复',
  `reply_time`    DATETIME     NULL COMMENT '回复时间',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time`   DATETIME     NULL ON UPDATE CURRENT_TIMESTAMP,
  `deleted`       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_item` (`order_item_id`),
  KEY `idx_product_status` (`product_id`, `status`),
  KEY `idx_user` (`user_id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品评价';
