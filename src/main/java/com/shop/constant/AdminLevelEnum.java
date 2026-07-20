package com.shop.constant;

import lombok.Getter;

/**
 * 管理员级别枚举
 *
 * @author shop
 */
@Getter
public enum AdminLevelEnum {

    NONE(0, "非管理员"),
    NORMAL(1, "普通管理员"),
    SUPER(2, "超级管理员");

    private final int value;
    private final String label;

    AdminLevelEnum(int value, String label) {
        this.value = value;
        this.label = label;
    }

    public static AdminLevelEnum of(int value) {
        for (AdminLevelEnum l : values()) {
            if (l.value == value) return l;
        }
        return NONE;
    }

    /**
     * 判断是否有权限操作目标用户
     *
     * @param operatorLevel 操作人级别
     * @param targetLevel   目标用户级别
     * @return true = 允许操作
     */
    public static boolean canOperate(int operatorLevel, int targetLevel) {
        // 超级管理员可操作任何人
        if (operatorLevel >= SUPER.value) return true;
        // 普通管理员不可操作超级管理员，可操作普通管理员及以下
        return operatorLevel >= NORMAL.value && targetLevel < SUPER.value;
    }
}
