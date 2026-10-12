-- 个性化实时推荐子系统 - 推荐库初始化数据
-- 执行方式：mysql -u root -p < rec-init-data.sql
-- 依赖：先执行 rec-schema.sql 建库建表
-- 对应文档：《详细设计说明书》9.2 配置参数示例
--
-- 幂等说明：全部使用 INSERT IGNORE，重复执行不会覆盖运营已修改的配置值；
-- 如需把某项恢复为默认值，请通过管理后台修改，不建议直接改库。

SET NAMES utf8mb4;

USE shop_rec;

-- ========== 推荐策略配置（25 项） ==========
-- 配置键 = config_key，默认值 = config_value，与详细设计 9.2 逐条对应；
-- 内存库副本由推荐服务启动时自动加载（见 RecConfigServiceImpl）。

INSERT IGNORE INTO t_rec_config (id, config_key, config_value, value_type, group_name, remark) VALUES
-- 行为权值：行为类型折算分值，权重越高对偏好分贡献越大
(1,  'weight.behavior.view',       '1',    'decimal', '行为权值', '浏览行为权值（弱兴趣信号，量最大）'),
(2,  'weight.behavior.add_cart',   '3',    'decimal', '行为权值', '加购行为权值（中等兴趣信号）'),
(3,  'weight.behavior.favorite',   '4',    'decimal', '行为权值', '收藏行为权值（强兴趣信号）'),
(4,  'weight.behavior.purchase',   '8',    'decimal', '行为权值', '购买行为权值（最强兴趣信号）'),
-- 窗口权值：六项之和必须为 1，体现「近期行为权重更高」
(5,  'weight.window.1min',         '0.30', 'decimal', '窗口权值', '1 分钟窗口权值'),
(6,  'weight.window.5min',         '0.20', 'decimal', '窗口权值', '5 分钟窗口权值'),
(7,  'weight.window.10min',        '0.15', 'decimal', '窗口权值', '10 分钟窗口权值'),
(8,  'weight.window.1h',           '0.15', 'decimal', '窗口权值', '1 小时窗口权值'),
(9,  'weight.window.24h',          '0.15', 'decimal', '窗口权值', '24 小时窗口权值'),
(10, 'weight.window.7d',           '0.05', 'decimal', '窗口权值', '7 天窗口权值'),
-- 特征计算
(11, 'feature.cap.threshold',      '100',  'decimal', '特征计算', '单维度偏好分封顶阈值，防止单一偏好无限膨胀'),
-- 召回：两路配额与候选规模
(12, 'recall.ratio',               '70:30','string',  '召回',     '两路召回占比（类目:热门），两数之和必须为 100'),
(13, 'recall.pool.size',           '200',  'int',     '召回',     '候选池目标规模'),
(14, 'recall.category.topN',       '3',    'int',     '召回',     '类目偏好召回取用的偏好类目数'),
(15, 'recall.category.limit',      '50',   'int',     '召回',     '单类目候选条数'),
(16, 'recall.hot.limit',           '60',   'int',     '召回',     '热门召回候选条数'),
-- 打分：三项之和必须为 1
(17, 'rank.weight.cat',            '0.6',  'decimal', '打分',     '类目偏好分权值'),
(18, 'rank.weight.hot',            '0.4',  'decimal', '打分',     '商品热度分权值'),
(19, 'rank.weight.brand',          '0',    'decimal', '打分',     '品牌偏好分权值（预留，默认不启用）'),
-- 缓存
(20, 'cache.result.ttl',           '300',  'int',     '缓存',     '榜单快照有效期（秒），同一用户翻页顺序一致的保证时长'),
-- 采集防护
(21, 'collect.sample.rate',        '1.0',  'decimal', '采集防护', '事件采样率，高并发场景可下调以降低链路压力'),
(22, 'limit.report.perMinute',     '60',   'int',     '采集防护', '单用户每分钟上报上限'),
(23, 'limit.report.itemPerMinute', '10',   'int',     '采集防护', '单用户单商品单行为类型每分钟上报上限'),
-- 过滤
(24, 'exclude.recent.days',        '7',    'int',     '过滤',     '已购与已加购商品的排除天数，取 0 表示不启用该过滤规则'),
-- 开关（高危项：仅指定运营账号可改，强制记录失效时间并默认 30 分钟后自动复位）
(25, 'degrade.force',              '正常', 'string',  '开关',     '强制降级状态，仅用于演练与故障隔离，取值须属于 7 种固定降级状态');

-- ========== 初始化校验 ==========
SELECT group_name AS 分组, COUNT(*) AS 配置项数
FROM t_rec_config
GROUP BY group_name
ORDER BY group_name;

SELECT COUNT(*) AS 配置项总数 FROM t_rec_config;
