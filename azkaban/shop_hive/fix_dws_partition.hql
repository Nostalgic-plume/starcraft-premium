-- ============================================================
-- 修复 DWS 表：重建为分区表
-- 在 hadoop102 上执行：
--   hive -f fix_dws_partition.hql
-- ============================================================
SET hive.execution.engine=mr;

DROP TABLE IF EXISTS dws.dws_order_daily;
CREATE EXTERNAL TABLE dws.dws_order_daily (
    order_count       BIGINT          COMMENT '订单数（去重）',
    paid_order_count  BIGINT          COMMENT '已支付订单数（状态1/2/3/4）',
    completed_count   BIGINT          COMMENT '已完成订单数（状态4）',
    cancelled_count   BIGINT          COMMENT '已取消订单数（状态5）',
    total_amount      DECIMAL(16,2)   COMMENT '订单总金额',
    actual_amount     DECIMAL(16,2)   COMMENT '实际支付总金额（GMV）',
    discount_amount   DECIMAL(16,2)   COMMENT '优惠总金额',
    user_count        BIGINT          COMMENT '下单用户数（去重）',
    avg_order_amount  DECIMAL(10,2)   COMMENT '客单价（实际金额/订单数）',
    product_sold      BIGINT          COMMENT '销售商品件数（总quantity）'
) COMMENT '订单日汇总——全局订单指标'
PARTITIONED BY (dt STRING COMMENT '分区：数据日期')
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/dws/dws_order_daily';

DROP TABLE IF EXISTS dws.dws_product_sale_daily;
CREATE EXTERNAL TABLE dws.dws_product_sale_daily (
    product_id    BIGINT          COMMENT '商品ID',
    product_name  STRING          COMMENT '商品名称',
    category_name STRING          COMMENT '分类名称',
    sale_quantity BIGINT          COMMENT '销售数量',
    sale_amount   DECIMAL(16,2)   COMMENT '销售额',
    order_count   BIGINT          COMMENT '订单数（去重）'
) COMMENT '商品销售日汇总——商品维度销售排行榜'
PARTITIONED BY (dt STRING COMMENT '分区：数据日期')
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/dws/dws_product_sale_daily';

DROP TABLE IF EXISTS dws.dws_shop_daily;
CREATE EXTERNAL TABLE dws.dws_shop_daily (
    shop_id       BIGINT          COMMENT '店铺ID',
    gmv           DECIMAL(16,2)   COMMENT 'GMV（排除已取消/退款订单）',
    order_count   BIGINT          COMMENT '订单数（去重）',
    product_count BIGINT          COMMENT '销售商品种类数（去重）',
    user_count    BIGINT          COMMENT '下单用户数（去重）'
) COMMENT '店铺日汇总——店铺维度销售KPI'
PARTITIONED BY (dt STRING COMMENT '分区：数据日期')
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/dws/dws_shop_daily';

DROP TABLE IF EXISTS dws.dws_user_active_daily;
CREATE EXTERNAL TABLE dws.dws_user_active_daily (
    dau              BIGINT         COMMENT '日活跃用户数（下单用户去重）',
    new_user_count   BIGINT         COMMENT '当日新增用户数（拉链表start_date=当天）',
    order_user_count BIGINT         COMMENT '下单用户数（去重）',
    paid_user_count  BIGINT         COMMENT '支付用户数（状态1/2/3/4）',
    avg_user_order   DECIMAL(5,2)   COMMENT '人均下单数（总订单数/下单用户数）'
) COMMENT '用户活跃日汇总——DAU及用户活跃指标'
PARTITIONED BY (dt STRING COMMENT '分区：数据日期')
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/dws/dws_user_active_daily';
