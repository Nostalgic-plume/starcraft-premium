-- ============================================================
-- Hive ODS 全量表 DDL（分区表，dt string）
-- 映射 MySQL shop 数据库结构，字段分隔符 '\001'
-- 使用前：DROP 操作会清空数据，请确认已备份
-- ============================================================

SET hive.execution.engine=mr;

-- 1. address 地址表
DROP TABLE IF EXISTS ods.ods_address_full;
CREATE EXTERNAL TABLE ods.ods_address_full (
  id           BIGINT,
  user_id      BIGINT,
  receiver_name STRING,
  receiver_phone STRING,
  province     STRING,
  city         STRING,
  district     STRING,
  detail       STRING,
  is_default   TINYINT,
  create_time  STRING,
  update_time  STRING,
  deleted      TINYINT
) PARTITIONED BY (dt STRING)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ods/ods_address_full';

-- 2. admin 管理员表
DROP TABLE IF EXISTS ods.ods_admin_full;
CREATE EXTERNAL TABLE ods.ods_admin_full (
  id           BIGINT,
  username     STRING,
  password     STRING,
  nickname     STRING,
  admin_level  TINYINT,
  status       TINYINT,
  create_time  STRING,
  update_time  STRING,
  deleted      TINYINT
) PARTITIONED BY (dt STRING)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ods/ods_admin_full';

-- 3. cart 购物车表
DROP TABLE IF EXISTS ods.ods_cart_full;
CREATE EXTERNAL TABLE ods.ods_cart_full (
  id           BIGINT,
  user_id      BIGINT,
  product_id   BIGINT,
  sku_id       BIGINT,
  quantity     INT,
  checked      TINYINT,
  is_ordered   TINYINT,
  create_time  STRING,
  update_time  STRING,
  deleted      TINYINT
) PARTITIONED BY (dt STRING)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ods/ods_cart_full';

-- 4. category 分类表
DROP TABLE IF EXISTS ods.ods_category_full;
CREATE EXTERNAL TABLE ods.ods_category_full (
  id           BIGINT,
  name         STRING,
  parent_id    BIGINT,
  sort         INT,
  icon         STRING,
  status       TINYINT,
  create_time  STRING,
  update_time  STRING,
  deleted      TINYINT
) PARTITIONED BY (dt STRING)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ods/ods_category_full';

-- 5. permission 权限表
DROP TABLE IF EXISTS ods.ods_permission_full;
CREATE EXTERNAL TABLE ods.ods_permission_full (
  id           BIGINT,
  name         STRING,
  code         STRING,
  parent_id    BIGINT,
  sort         INT,
  status       TINYINT,
  create_time  STRING,
  update_time  STRING,
  deleted      TINYINT
) PARTITIONED BY (dt STRING)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ods/ods_permission_full';

-- 6. role 角色表
DROP TABLE IF EXISTS ods.ods_role_full;
CREATE EXTERNAL TABLE ods.ods_role_full (
  id           BIGINT,
  name         STRING,
  code         STRING,
  description  STRING,
  status       TINYINT,
  create_time  STRING,
  update_time  STRING,
  deleted      TINYINT
) PARTITIONED BY (dt STRING)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ods/ods_role_full';

-- 7. role_permission 角色权限关联表
DROP TABLE IF EXISTS ods.ods_role_permission_full;
CREATE EXTERNAL TABLE ods.ods_role_permission_full (
  id             BIGINT,
  role_id        BIGINT,
  permission_id  BIGINT,
  create_time    STRING
) PARTITIONED BY (dt STRING)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ods/ods_role_permission_full';

-- 8. shop 店铺表
DROP TABLE IF EXISTS ods.ods_shop_full;
CREATE EXTERNAL TABLE ods.ods_shop_full (
  id             BIGINT,
  user_id        BIGINT,
  username       STRING,
  password       STRING,
  shop_name      STRING,
  shop_logo      STRING,
  shop_desc      STRING,
  contact_name   STRING,
  contact_phone  STRING,
  status         INT,
  create_time    STRING,
  update_time    STRING,
  deleted        INT
) PARTITIONED BY (dt STRING)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ods/ods_shop_full';

-- 9. user_role 用户角色关联表
DROP TABLE IF EXISTS ods.ods_user_role_full;
CREATE EXTERNAL TABLE ods.ods_user_role_full (
  id           BIGINT,
  user_id      BIGINT,
  role_id      BIGINT,
  create_time  STRING
) PARTITIONED BY (dt STRING)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ods/ods_user_role_full';

-- 10. user_status_log 用户状态变更日志表
DROP TABLE IF EXISTS ods.ods_user_status_log_full;
CREATE EXTERNAL TABLE ods.ods_user_status_log_full (
  id           BIGINT,
  user_id      BIGINT,
  operator_id  BIGINT,
  from_status  TINYINT,
  to_status    TINYINT,
  reason       STRING,
  create_time  STRING
) PARTITIONED BY (dt STRING)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ods/ods_user_status_log_full';

-- 11. product 商品表（全量，按天覆盖）
-- 解决增量导入因 update_time 窗口限制遗漏历史产品的问题
DROP TABLE IF EXISTS ods.ods_product_full;
CREATE EXTERNAL TABLE ods.ods_product_full (
  id                BIGINT,
  name              STRING,
  description       STRING,
  category_id       BIGINT,
  first_category_id BIGINT,
  brand             STRING,
  main_image        STRING,
  images            STRING,
  detail            STRING,
  shop_id           BIGINT,
  status            TINYINT,
  create_time       STRING,
  update_time       STRING,
  deleted           TINYINT
) PARTITIONED BY (dt STRING)
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
STORED AS TEXTFILE
LOCATION '/warehouse/ods/ods_product_full';

-- ============================================================
-- 建表完成
-- ============================================================
SHOW TABLES IN ods LIKE '*_full';
