package com.shop.rec.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.Result;
import com.shop.rec.common.RecRequestContext;
import com.shop.rec.config.RecProperties;
import com.shop.rec.constant.RecErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 内部调用凭证过滤器
 *
 * <p>
 * 校验商城后端调用推荐服务时携带的 {@code X-Internal-Token}，确认调用方身份，
 * 防止绕过商城后端直接访问推荐服务伪造用户身份。同时把链路标识与用户标识写入
 * {@link RecRequestContext} 供后续环节读取。
 *
 * <p>
 * <b>作用范围</b>：仅拦截推荐服务内部接口（{@value #INTERNAL_PATH_PREFIX} 前缀）。
 * 管理后台接口（配置、坑位、规则、画像回滚）走运营角色鉴权，行为上报接口走登录态鉴权，
 * 均不受本过滤器影响。
 *
 * <p>
 * <b>失败语义</b>：凭证缺失或不匹配时拒绝服务并返回错误码
 * {@link RecErrorCode#INTERNAL_TOKEN_INVALID}，HTTP 状态保持成功（降级与拒绝均不改变
 * HTTP 状态，避免调用方进入错误分支）。凭证未配置时同样拒绝，并记录告警提示配置缺失。
 *
 * @author shop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 请求头：内部调用凭证 */
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    /** 请求头：用户标识（由商城后端从登录态解析后透传，缺失表示游客） */
    public static final String HEADER_USER_ID = "X-User-Id";

    /** 请求头：链路标识，用于日志与指标关联 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** 需要内部凭证的接口路径前缀 */
    private static final String INTERNAL_PATH_PREFIX = "/api/rec/home";

    private final RecProperties recProperties;

    private final ObjectMapper objectMapper;

    /**
     * 对内部接口执行凭证校验并建立请求上下文
     *
     * @param request  当前请求
     * @param response 当前响应
     * @param chain    过滤器链
     * @throws ServletException Servlet 处理异常
     * @throws IOException      IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();
        if (!uri.startsWith(INTERNAL_PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String traceId = request.getHeader(HEADER_TRACE_ID);
        String expectedToken = recProperties.getInternalToken();
        String actualToken = request.getHeader(HEADER_INTERNAL_TOKEN);

        if (expectedToken == null || expectedToken.isBlank() || !expectedToken.equals(actualToken)) {
            log.warn("内部调用凭证非法, uri: {}, traceId: {}, 凭证是否已配置: {}",
                    uri, traceId, expectedToken != null && !expectedToken.isBlank());
            writeReject(response, traceId);
            return;
        }

        RecRequestContext.set(traceId, parseUserId(request.getHeader(HEADER_USER_ID)));
        try {
            chain.doFilter(request, response);
        } finally {
            RecRequestContext.clear();
        }
    }

    /**
     * 输出拒绝响应
     *
     * @param response 当前响应
     * @param traceId  链路标识，用于日志关联
     * @throws IOException 写出响应失败时抛出
     */
    private void writeReject(HttpServletResponse response, String traceId) throws IOException {
        log.warn("内部调用被拒绝, traceId: {}", traceId);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.error(RecErrorCode.INTERNAL_TOKEN_INVALID, "内部调用凭证非法")));
    }

    /**
     * 解析用户标识请求头
     *
     * <p>缺失或格式非法时返回 null，由调用方按游客策略处理。
     *
     * @param rawUserId 请求头原始值
     * @return 用户标识；无效时返回 null
     */
    private Long parseUserId(String rawUserId) {
        if (rawUserId == null || rawUserId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(rawUserId.trim());
        } catch (NumberFormatException e) {
            log.warn("用户标识请求头格式非法, 原始值: {}", rawUserId);
            return null;
        }
    }
}
