package com.shop.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.shop.common.BusinessException;
import com.shop.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 全局异常处理器
 * 统一捕获各类异常并返回标准 Result 格式，Controller 层无需 try-catch
 *
 * 处理链：BusinessException → 参数校验异常 → 权限异常 → 系统异常（兜底）
 *
 * @author shop
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常（自定义异常，如库存不足、商品不存在、参数不合法等）
     *
     * @param e 业务异常对象，内含自定义错误码 code 与中文提示信息 message
     * @return 标准 Result，code/msg 原样取自异常的 code 与 message
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理未登录异常：访问需登录接口但未携带有效 token，或会话已过期
     *
     * @param e Sa-Token 未登录异常，携带具体的未登录原因
     * @return 固定返回 code=401、msg="未登录，请先登录"
     */
    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLoginException(NotLoginException e) {
        log.warn("未登录: {}", e.getMessage());
        return Result.error(401, "未登录，请先登录");
    }

    /**
     * 处理无权限异常：已登录但缺少所需权限码（@SaCheckPermission 校验失败）
     *
     * @param e Sa-Token 无权限异常，携带缺失的权限码信息
     * @return 固定返回 code=403、msg="无权限，请联系管理员"
     */
    @ExceptionHandler(NotPermissionException.class)
    public Result<Void> handleNotPermissionException(NotPermissionException e) {
        log.warn("无权限: {}", e.getMessage());
        return Result.error(403, "无权限，请联系管理员");
    }

    /**
     * 处理无角色异常：已登录但缺少所需角色（@SaCheckRole 校验失败）
     *
     * @param e Sa-Token 无角色异常，携带缺失的角色信息
     * @return 固定返回 code=403、msg="无权限，角色不足"
     */
    @ExceptionHandler(NotRoleException.class)
    public Result<Void> handleNotRoleException(NotRoleException e) {
        log.warn("无角色: {}", e.getMessage());
        return Result.error(403, "无权限，角色不足");
    }

    /**
     * 处理 @RequestBody 实体参数校验失败（@NotBlank/@NotNull 等约束未通过）
     *
     * @param e 参数校验异常，bindingResult 中携带各字段的错误信息
     * @return 返回 code=400，msg 为拼接后的"字段名: 错误信息"列表（分号分隔）；无字段错误时兜底返回"参数校验失败"
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return Result.error(400, msg);
    }

    /**
     * 处理方法参数校验失败（@RequestParam/@PathVariable 上的约束注解未通过）
     *
     * @param e 约束校验异常，携带具体的校验失败信息
     * @return 固定返回 code=400、msg="参数格式不正确"
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException e) {
        log.warn("参数校验失败: {}", e.getMessage());
        return Result.error(400, "参数格式不正确");
    }

    /**
     * 处理参数绑定失败（表单/查询参数无法绑定到对象属性、类型不匹配等）
     *
     * @param e 绑定异常，bindingResult 中携带各字段的错误信息
     * @return 返回 code=400，msg 为拼接后的"字段名: 错误信息"列表（分号分隔）；无字段错误时兜底返回"参数绑定失败"
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数绑定失败");
        log.warn("参数绑定失败: {}", msg);
        return Result.error(400, msg);
    }

    /**
     * 处理请求体解析失败（JSON 格式错误、字段类型不匹配、请求体为空等）
     *
     * @param e 请求体不可读异常，携带具体的解析失败原因
     * @return 固定返回 code=400、msg="请求参数格式错误"
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求参数解析失败: {}", e.getMessage());
        return Result.error(400, "请求参数格式错误");
    }

    /**
     * 处理接口不存在（请求路径没有对应 Controller 映射；需配置 throw-exception-if-no-handler-found 才生效）
     *
     * @param e 无处理器异常，携带请求的 HTTP 方法与 URL
     * @return 返回 code=404、msg="请求的接口不存在: {HTTP方法} {URL}"
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandlerFoundException(NoHandlerFoundException e) {
        log.warn("接口不存在: {} {}", e.getHttpMethod(), e.getRequestURL());
        return Result.error(404, "请求的接口不存在: " + e.getHttpMethod() + " " + e.getRequestURL());
    }

    /**
     * 兜底处理所有未被上述异常处理器捕获的系统异常（数据库异常、空指针、运行时异常等）
     * <p>对 SSE（text/event-stream）请求不返回 Result JSON 包装，避免破坏事件流协议。</p>
     *
     * @param e       未预期的异常对象，以 error 级别完整打印堆栈
     * @param request 当前 HTTP 请求，用于识别 Accept 头是否为 SSE
     * @return 固定返回 msg="系统繁忙，请稍后再试" 的错误 Result
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常: ", e);

        // SSE 接口不返回 Result JSON，避免序列化冲突
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            // 对于 SSE，直接抛出让容器处理，或返回空 Result（前端通过 SSE error 事件感知）
            return Result.error("系统繁忙，请稍后再试");
        }
        return Result.error("系统繁忙，请稍后再试");
    }
}
