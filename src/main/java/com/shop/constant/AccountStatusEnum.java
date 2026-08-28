package com.shop.constant;

import lombok.Getter;

/**
 * 账号状态枚举（4 种状态）
 *
 * @author shop
 */
@Getter
public enum AccountStatusEnum {

    /** 正常：可正常登录并使用全部功能 */
    NORMAL(0, "正常"),
    /** 停用：禁止登录，可由管理员恢复为正常 */
    DISABLED(1, "停用"),
    /** 注销中：已申请注销，等待注销流程处理 */
    CANCELLING(2, "注销中"),
    /** 封禁中：因违规被封禁，登录时提示对应文案 */
    BANNED(3, "封禁中");

    private final int value;
    private final String label;

    AccountStatusEnum(int value, String label) {
        this.value = value;
        this.label = label;
    }

    /**
     * 根据状态编码查找对应枚举
     *
     * @param value 状态编码，取值范围 0~3（超出范围视为无效编码）
     * @return 匹配的枚举常量；未匹配到时安全回退为 {@link #NORMAL}（不抛异常）
     */
    public static AccountStatusEnum of(int value) {
        for (AccountStatusEnum s : values()) {
            if (s.value == value) return s;
        }
        return NORMAL;
    }

    /**
     * 根据状态编码获取中文状态文本
     *
     * @param value 状态编码，取值范围 0~3（超出范围按 NORMAL 处理）
     * @return 状态对应的中文文本；未匹配到编码时返回 NORMAL 的文本"正常"
     */
    public static String labelOf(int value) {
        return of(value).label;
    }
}
