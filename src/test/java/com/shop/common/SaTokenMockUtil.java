package com.shop.common;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.model.SaRequest;
import cn.dev33.satoken.dao.SaTokenDao;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Sa-Token 登录态 Mock 工具（仅测试用）
 * <p>
 * 三端独立会话重构后，生产代码通过 {@code StpUserUtil.INSTANCE / StpShopUtil.INSTANCE / StpAdminUtil.INSTANCE}
 * 的实例方法获取登录态，无法再通过 mockStatic(StpUtil) 拦截。
 * 本工具改为 mock 底层静态入口 {@link SaManager}：提供携带固定 token 的请求上下文 + token→loginId 的内存 Dao，
 * 使任意 StpLogic 实例的 getLoginIdAsLong() 返回指定 ID。
 * </p>
 * <pre>{@code
 * try (var login = SaTokenMockUtil.userLogin(1L)) { ... 测试体 ... }
 * }</pre>
 *
 * @author shop
 */
public final class SaTokenMockUtil {

    private SaTokenMockUtil() {
    }

    /**
     * 模拟指定 ID 已登录（StpUserUtil / StpShopUtil / StpAdminUtil 任一 loginType 均适用）
     *
     * @param loginId 登录 ID（用户 / 商家 / 管理员）
     * @return AutoCloseable，需 try-with-resources 关闭以释放 mock
     */
    public static AutoCloseable userLogin(Long loginId) {
        // ① mock 请求上下文：任意 tokenName 的请求参数/请求头均返回固定 token
        SaRequest request = mock(SaRequest.class);
        when(request.getParameter(anyString())).thenReturn("mock-token");
        when(request.getHeader(anyString())).thenReturn("mock-token");
        when(request.getCookie(anyString())).thenReturn(null);
        SaTokenContext context = mock(SaTokenContext.class);
        when(context.getRequest()).thenReturn(request);

        // ② mock SaManager：请求上下文 + token→loginId 的 Dao + 保留真实 config
        MockedStatic<SaManager> manager = mockStatic(SaManager.class);
        when(SaManager.getSaTokenContext()).thenReturn(context);
        when(SaManager.getConfig()).thenCallRealMethod();
        SaTokenDao dao = mock(SaTokenDao.class);
        when(SaManager.getSaTokenDao()).thenReturn(dao);
        when(dao.getLoginIdByToken("mock-token")).thenReturn(String.valueOf(loginId));

        return () -> manager.close();
    }
}
