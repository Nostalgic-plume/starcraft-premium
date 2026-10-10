package com.shop.rec.constant;

/**
 * 推荐子系统错误码常量
 *
 * <p>
 * 严格对应《详细设计说明书》5.3 完整错误码表。按用途分为四段：
 * <ul>
 *     <li>1xxx 参数与鉴权类：提示用户，不降级</li>
 *     <li>2xxx 已降级类：照常渲染榜单</li>
 *     <li>3xxx 服务异常类：使用兜底结果</li>
 *     <li>4xxx 配置与运营操作非法类：保留旧配置并提示操作人</li>
 * </ul>
 *
 * <p>
 * 使用方式：{@code throw new BusinessException(RecErrorCode.PARAM_INVALID, "参数校验失败：eventType 取值非法")}，
 * 由全局异常处理器原样返回 code 与 msg。禁止在业务代码中自定义本类之外的码值。
 *
 * @author shop
 */
public final class RecErrorCode {

    private RecErrorCode() {
    }

    // ==================== 成功 ====================

    /** 处理成功 */
    public static final int SUCCESS = 200;

    // ==================== 1xxx 参数与鉴权类 ====================

    /** 参数校验失败（含 E-01、E-02、E-03、E-04 的入参非法） */
    public static final int PARAM_INVALID = 1001;

    /** 登录态缺失或失效：行为上报不采集，榜单查询按游客策略返回热门内容 */
    public static final int NOT_LOGIN = 1002;

    /** 商品不存在或已删除：静默丢弃事件 */
    public static final int ITEM_NOT_FOUND = 1003;

    /** 行为时间越界：事件时间与服务器时间偏差超过 10 分钟，静默丢弃 */
    public static final int EVENT_TIME_INVALID = 1004;

    /** 事件重复上报：幂等去重命中，静默丢弃 */
    public static final int EVENT_DUPLICATED = 1005;

    /** 上报频率超限：静默丢弃 */
    public static final int REPORT_LIMITED = 1006;

    /** 采样式丢弃：静默丢弃 */
    public static final int SAMPLED_OUT = 1007;

    /** 爬虫来源丢弃：静默丢弃 */
    public static final int CRAWLER_DROPPED = 1008;

    /** 内部调用凭证非法：拒绝服务，商城后端返回原生热销榜 */
    public static final int INTERNAL_TOKEN_INVALID = 1009;

    // ==================== 2xxx 已降级类 ====================

    /** 特征读取失败：按无特征处理，展示热度排序结果 */
    public static final int FEATURE_READ_FAILED = 2001;

    /** 类目偏好召回异常：丢弃该路，展示热门召回结果 */
    public static final int CATEGORY_RECALL_FAILED = 2002;

    /** 候选池或榜单为空：展示兜底名单 */
    public static final int CANDIDATE_EMPTY = 2003;

    /** 冷启动且无个性化输入：仅执行热门召回 */
    public static final int COLD_START = 2004;

    /** 打分与重排异常：展示按热度降序的结果 */
    public static final int RANK_FAILED = 2005;

    /** 兜底名单为空：展示实时热度榜前若干名 */
    public static final int FALLBACK_EMPTY = 2006;

    // ==================== 3xxx 服务异常类 ====================

    /** 推荐服务内部异常：展示兜底名单并告警 */
    public static final int SERVICE_INTERNAL_ERROR = 3001;

    /** 推荐服务不可达或最终无商品：由商城后端返回原生热销榜 */
    public static final int SERVICE_UNREACHABLE = 3002;

    /** 榜单快照读写异常：结果一致，仅耗时上升，记录告警 */
    public static final int SNAPSHOT_ERROR = 3003;

    /** 配置副本同步失败：回滚数据库配置，旧配置继续生效 */
    public static final int CONFIG_SYNC_FAILED = 3004;

    /** 指标写入失败：仅写日志，不影响业务 */
    public static final int METRIC_WRITE_FAILED = 3005;

    /** 消息投递失败：直接落库补偿并告警 */
    public static final int MESSAGE_SEND_FAILED = 3006;

    /** 画像生成失败或超时：沿用上一有效版本，不切换有效性 */
    public static final int PROFILE_TASK_FAILED = 3007;

    /** 商品数据加载或热度刷新失败：沿用上一版数据与索引 */
    public static final int ITEM_DATA_LOAD_FAILED = 3008;

    // ==================== 4xxx 配置与运营操作非法类 ====================

    /** 配置项非法（类型、区间或组合校验不通过）：拒绝写入，保留旧值 */
    public static final int CONFIG_INVALID = 4001;

    /** 坑位位置冲突：同一时段同一位置已存在启用记录，拒绝写入 */
    public static final int SLOT_POSITION_CONFLICT = 4002;

    /** 权限不足：拒绝请求 */
    public static final int PERMISSION_DENIED = 4003;

    /** 回滚目标版本不可用：目标版本不在保留范围内，保持当前有效版本不变 */
    public static final int PROFILE_VERSION_UNAVAILABLE = 4004;
}
