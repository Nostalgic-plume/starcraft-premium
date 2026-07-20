-- ============================================================
-- 修复 ADS 表：重建 6 张分区表
-- 在 hadoop102 上执行：
--   hive -f fix_ads_partition.hql
-- ============================================================
SET hive.execution.engine=mr;

-- ============================================================
-- 1. ads_overview
-- ============================================================
DROP TABLE IF EXISTS ads.ads_overview;
CREATE EXTERNAL TABLE ads.ads_overview (
    total_users      BIGINT          COMMENT '累计用户数',
    total_products   BIGINT          COMMENT '累计商品数',
    total_orders     BIGINT          COMMENT '当日订单数',
    total_gmv        DECIMAL(16,2)   COMMENT '当日 GMV',
    avg_order_price  DECIMAL(10,2)   COMMENT '当日客单价'
) COMMENT '全局概览'
PARTITIONED BY (dt STRING COMMENT '分区：数据日期')
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ads/ads_overview';

-- ============================================================
-- 2. ads_gmv_daily
-- ============================================================
DROP TABLE IF EXISTS ads.ads_gmv_daily;
CREATE EXTERNAL TABLE ads.ads_gmv_daily (
    gmv              DECIMAL(16,2)   COMMENT '当日 GMV',
    order_count      BIGINT          COMMENT '当日订单数',
    paid_order_count BIGINT          COMMENT '当日支付订单数',
    paid_user_count  BIGINT          COMMENT '当日支付用户数',
    avg_order_amount DECIMAL(10,2)   COMMENT '当日客单价'
) COMMENT 'GMV 日报'
PARTITIONED BY (dt STRING COMMENT '分区：数据日期')
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ads/ads_gmv_daily';

-- ============================================================
-- 3. ads_order_stats_daily
-- ============================================================
DROP TABLE IF EXISTS ads.ads_order_stats_daily;
CREATE EXTERNAL TABLE ads.ads_order_stats_daily (
    total_orders     INT    COMMENT '当日总订单数',
    paid_orders      INT    COMMENT '已支付订单数',
    shipped_orders   INT    COMMENT '已发货订单数',
    completed_orders INT    COMMENT '已完成订单数',
    cancelled_orders INT    COMMENT '已取消订单数',
    refund_orders    INT    COMMENT '已退款订单数'
) COMMENT '订单状态分布'
PARTITIONED BY (dt STRING COMMENT '分区：数据日期')
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ads/ads_order_stats_daily';

-- ============================================================
-- 4. ads_user_active
-- ============================================================
DROP TABLE IF EXISTS ads.ads_user_active;
CREATE EXTERNAL TABLE ads.ads_user_active (
    dau              BIGINT  COMMENT '日活跃用户',
    order_user_count BIGINT  COMMENT '下单用户数'
) COMMENT '用户活跃'
PARTITIONED BY (dt STRING COMMENT '分区：数据日期')
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ads/ads_user_active';

-- ============================================================
-- 5. ads_user_new_retain
-- ============================================================
DROP TABLE IF EXISTS ads.ads_user_new_retain;
CREATE EXTERNAL TABLE ads.ads_user_new_retain (
    new_users         INT           COMMENT '当日新增用户数',
    day1_retain_users INT           COMMENT '次日留存用户数',
    day1_retain_rate  DECIMAL(5,2)  COMMENT '次日留存率（%）',
    day7_retain_users INT           COMMENT '7日留存用户数',
    day7_retain_rate  DECIMAL(5,2)  COMMENT '7日留存率（%）',
    day30_retain_users INT          COMMENT '30日留存用户数',
    day30_retain_rate DECIMAL(5,2)  COMMENT '30日留存率（%）'
) COMMENT '用户新增与留存'
PARTITIONED BY (dt STRING COMMENT '分区：数据日期')
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ads/ads_user_new_retain';

-- ============================================================
-- 6. ads_shop_daily
-- ============================================================
DROP TABLE IF EXISTS ads.ads_shop_daily;
CREATE EXTERNAL TABLE ads.ads_shop_daily (
    shop_name     STRING          COMMENT '店铺名称',
    gmv           DECIMAL(16,2)   COMMENT 'GMV',
    order_count   BIGINT          COMMENT '订单数',
    product_count BIGINT          COMMENT '销售商品种类数'
) COMMENT '店铺日报'
PARTITIONED BY (dt STRING COMMENT '分区：数据日期')
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ads/ads_shop_daily';
