package com.shop.constant;

import lombok.Getter;

/**
 * 账号状态枚举（4 种状态）
 *
 * @author shop
 */
@Getter
public enum AccountStatusEnum {

    NORMAL(0, "正常"),
    DISABLED(1, "停用"),
    CANCELLING(2, "注销中"),
    BANNED(3, "封禁中");

    private final int value;
    private final String label;

    AccountStatusEnum(int value, String label) {
        this.value = value;
        this.label = label;
    }

    public static AccountStatusEnum of(int value) {
        for (AccountStatusEnum s : values()) {
            if (s.value == value) return s;
        }
        return NORMAL;
    }

    public static String labelOf(int value) {
        return of(value).label;
    }
}
