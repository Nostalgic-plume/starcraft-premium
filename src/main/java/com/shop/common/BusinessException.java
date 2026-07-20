package com.shop.common;

import java.io.Serializable;

/**
 * 业务异常
 * 在业务逻辑中抛出自定义异常，由全局异常处理器统一捕获并返回前端
 * 异常信息包含中文描述和上下文参数，禁止纯错误码
 *
 * @see com.shop.config.GlobalExceptionHandler
 */
public class BusinessException extends RuntimeException implements Serializable {

    private Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.code = 500;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
