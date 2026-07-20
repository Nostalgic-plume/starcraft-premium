package com.shop.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一返回结果
 * 所有 Controller 接口通过此类返回，保证前端收到的响应格式一致
 *
 * @param <T> 数据类型
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

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "操作成功", data);
    }

    public static <T> Result<T> success(String msg, T data) {
        return new Result<>(200, msg, data);
    }

    public static <T> Result<T> error(String msg) {
        return new Result<>(500, msg, null);
    }

    public static <T> Result<T> error(Integer code, String msg) {
        return new Result<>(code, msg, null);
    }
}
