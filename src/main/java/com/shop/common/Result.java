package com.shop.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一返回结果
 * 所有 Controller 接口通过此类返回，保证前端收到的响应格式一致
 *
 * @param <T> 数据类型
 * @author shop
 */
@Data
@Schema(description = "统一返回结果")
public class Result<T> implements Serializable {

    @Schema(description = "状态码", example = "200")
    private Integer code;

    @Schema(description = "提示信息", example = "操作成功")
    private String msg;

    @Schema(description = "数据")
    private T data;

    private Result() {}

    private Result(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    /**
     * 无数据体的成功响应（默认消息「操作成功」）
     *
     * @param <T> 泛型占位，实际无返回数据
     * @return 成功响应，code=200，data 为 null
     */
    public static <T> Result<T> success() {
        return new Result<>(200, "操作成功", null);
    }

    /**
     * 仅带自定义消息的成功响应（无数据体）
     * 专用于返回类型为 Result&lt;Void&gt; 的场景，避免类型推断冲突
     */
    public static <T> Result<T> success(String msg) {
        return new Result<>(200, msg, null);
    }

    /**
     * 带数据体的成功响应（默认消息「操作成功」）
     *
     * @param data 业务数据，可为 null
     * @param <T>  数据类型
     * @return 成功响应，code=200，msg=操作成功，data 为传入数据
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "操作成功", data);
    }

    /**
     * 带自定义消息和数据体的成功响应
     *
     * @param msg  提示信息
     * @param data 业务数据，可为 null
     * @param <T>  数据类型
     * @return 成功响应，code=200
     */
    public static <T> Result<T> success(String msg, T data) {
        return new Result<>(200, msg, data);
    }

    /**
     * 失败响应（默认状态码 500）
     *
     * @param msg 错误提示信息
     * @param <T> 数据类型
     * @return 失败响应，code=500，data 为 null
     */
    public static <T> Result<T> error(String msg) {
        return new Result<>(500, msg, null);
    }

    /**
     * 指定状态码的失败响应
     *
     * @param code 业务状态码（非 200 均视为失败，如 500 服务器异常、403 无权限）
     * @param msg  错误提示信息
     * @param <T>  数据类型
     * @return 失败响应，data 为 null
     */
    public static <T> Result<T> error(Integer code, String msg) {
        return new Result<>(code, msg, null);
    }
}
