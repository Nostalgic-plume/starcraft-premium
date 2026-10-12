package com.shop.rec.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 推荐请求上下文
 *
 * <p>
 * 由 {@link com.shop.rec.filter.InternalTokenFilter} 在请求进入时写入、请求结束时清理，
 * 供 M-08 及各环节读取当前请求的链路标识与用户身份。
 *
 * <p>
 * <b>身份来源约定</b>：用户标识只能来自商城后端解析后透传的请求头，
 * 严禁从请求参数中取值，防止越权查询他人推荐结果。
 *
 * @author shop
 */
public final class RecRequestContext {

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private RecRequestContext() {
    }

    /**
     * 写入当前请求上下文
     *
     * @param traceId 链路标识
     * @param userId  用户标识，游客为 null
     */
    public static void set(String traceId, Long userId) {
        HOLDER.set(new Context(traceId, userId));
    }

    /**
     * 读取当前请求的链路标识
     *
     * @return 链路标识；无上下文时返回 null
     */
    public static String getTraceId() {
        Context context = HOLDER.get();
        return context == null ? null : context.getTraceId();
    }

    /**
     * 读取当前请求的用户标识
     *
     * @return 用户标识；游客或无上下文时返回 null
     */
    public static Long getUserId() {
        Context context = HOLDER.get();
        return context == null ? null : context.getUserId();
    }

    /**
     * 判断当前请求是否为游客（无有效用户标识）
     *
     * @return 游客返回 true
     */
    public static boolean isGuest() {
        return getUserId() == null;
    }

    /**
     * 清理当前请求上下文
     *
     * <p>必须在请求处理完成后调用，避免线程复用导致上下文串号。
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 请求上下文数据结构
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Context {

        /** 链路标识，由商城后端生成并透传 */
        private String traceId;

        /** 用户标识，由商城后端从登录态解析后透传 */
        private Long userId;
    }
}
