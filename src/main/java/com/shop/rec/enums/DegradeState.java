package com.shop.rec.enums;

/**
 * 推荐结果降级状态
 *
 * <p>
 * 取值固定为 7 种，不允许新增或改名（对应《详细设计说明书》2.4 降级状态总览）。
 * 降级返回的 HTTP 状态均为成功，降级信息只通过本枚举的对外文案传递，
 * 前端不因降级进入错误分支。
 *
 * <p>
 * <b>优先级</b>：同一次请求可能命中多个降级条件，最终对外只设置一个状态，
 * 按严重程度取最高者，顺序为
 * {@code 上游兜底 > 兜底名单 > 召回降级 > 特征缺失 > 冷启动 > 游客 > 正常}。
 *
 * @author shop
 */
public enum DegradeState {

    /** 正常：全链路无异常，返回完整个性化榜单 */
    NORMAL("正常", 1),

    /** 游客：无有效登录态或身份无法识别，直接返回热门榜单 */
    GUEST("游客", 2),

    /** 冷启动：无实时特征且无画像，仅执行热门召回 */
    COLD_START("冷启动", 3),

    /** 特征缺失：特征读取失败，或仅有画像无实时特征，仅按商品热度分排序 */
    FEATURE_MISSING("特征缺失", 4),

    /** 召回降级：类目偏好召回超时、异常或返回空集，丢弃该路保留热门召回结果 */
    RECALL_DEGRADED("召回降级", 5),

    /** 兜底名单：候选池或榜单为空，或推荐服务内部异常，使用预生成的热度兜底名单 */
    FALLBACK_LIST("兜底名单", 6),

    /** 上游兜底：推荐服务整体不可用，由商城后端返回原生热销榜 */
    UPSTREAM_FALLBACK("上游兜底", 7);

    /** 对外文案，直接作为响应中的 degraded 字段值 */
    private final String text;

    /** 严重程度，数值越大越严重，用于多条件命中时取最严重者 */
    private final int severity;

    DegradeState(String text, int severity) {
        this.text = text;
        this.severity = severity;
    }

    /**
     * 获取对外文案
     *
     * @return 降级状态的对外文案，如「召回降级」
     */
    public String getText() {
        return text;
    }

    /**
     * 获取严重程度
     *
     * @return 严重程度数值，越大越严重
     */
    public int getSeverity() {
        return severity;
    }

    /**
     * 比较两个降级状态，返回更严重的一个
     *
     * <p>用于同一次请求命中多个降级条件时，按优先级确定最终对外状态。
     * 任一参数为 null 时返回另一个；两者均为 null 时返回 {@link #NORMAL}。
     *
     * @param left  候选状态一，可为 null
     * @param right 候选状态二，可为 null
     * @return 更严重的一方；两者均为 null 时返回正常
     */
    public static DegradeState mostSevere(DegradeState left, DegradeState right) {
        if (left == null) {
            return right == null ? NORMAL : right;
        }
        if (right == null) {
            return left;
        }
        return left.severity >= right.severity ? left : right;
    }

    /**
     * 按对外文案反查降级状态
     *
     * <p>用于配置项 degrade.force 的取值校验与解析。
     *
     * @param text 对外文案，如「召回降级」
     * @return 匹配的降级状态；无匹配时返回 null
     */
    public static DegradeState fromText(String text) {
        if (text == null) {
            return null;
        }
        for (DegradeState state : values()) {
            if (state.text.equals(text)) {
                return state;
            }
        }
        return null;
    }
}
