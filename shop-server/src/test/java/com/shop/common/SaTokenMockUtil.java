package com.shop.common;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.model.SaRequest;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.context.model.SaResponse;
import cn.dev33.satoken.context.model.SaStorage;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.stp.StpInterface;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
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
     * @return SaTokenLoginScope，需 try-with-resources 关闭以释放 mock
     */
    public static SaTokenLoginScope userLogin(Long loginId) {
        return userLogin(loginId, null);
    }

    /**
     * 模拟指定 ID 已登录，并指定 Sa-Token 权限加载器（用于 getRoleList / getPermissionList 场景）
     *
     * @param loginId     登录 ID
     * @param stpInterface 权限加载器（null 表示不注册，权限类调用会返回空/报错）
     * @return SaTokenLoginScope，需 try-with-resources 关闭以释放 mock
     */
    public static SaTokenLoginScope userLogin(Long loginId, StpInterface stpInterface) {
        // 提前触发 StpUserUtil 类初始化（其构造注册到真实 SaManager），
        // 避免 mockStatic 建立后再访问 INSTANCE 触发 <clinit> 与 stub 过程冲突（UnfinishedStubbing）
        StpUserUtil.INSTANCE.hashCode();

        // ① mock 请求上下文：任意 tokenName 的请求参数/请求头均返回固定 token
        // lenient：本工具被多个测试共享，各测试只用到其中部分 stub，故全部放宽避免 UnnecessaryStubbing
        SaRequest request = mock(SaRequest.class);
        lenient().when(request.getParam(anyString())).thenReturn("mock-token");
        lenient().when(request.getHeader(anyString())).thenReturn("mock-token");
        lenient().when(request.getCookieValue(anyString())).thenReturn("mock-token");
        SaTokenContext context = mock(SaTokenContext.class);
        lenient().when(context.getRequest()).thenReturn(request);
        lenient().when(context.getStorage()).thenReturn(mock(SaStorage.class));
        lenient().when(context.getResponse()).thenReturn(mock(SaResponse.class));

        // ② mock SaManager：请求上下文 + token→loginId 的 Dao + 提供默认 config
        MockedStatic<SaManager> manager = mockStatic(SaManager.class);
        lenient().when(SaManager.getSaTokenContext()).thenReturn(context);
        lenient().when(SaManager.getConfig()).thenReturn(new SaTokenConfig());
        // 登录动作（StpXxxUtil.INSTANCE.login）内部经 SaManager.getStpLogic(type) 获取当前 StpLogic 再取 config；
        // mockStatic 会拦截 putStpLogic 导致注册表为空，这里直接返回真实 StpUserUtil 实例（config 全局共享，各 loginType 均适用）
        lenient().when(SaManager.getStpLogic(anyString())).thenReturn(StpUserUtil.INSTANCE);
        if (stpInterface != null) {
            lenient().when(SaManager.getStpInterface()).thenReturn(stpInterface);
        }
        SaTokenDao dao = mock(SaTokenDao.class);
        lenient().when(SaManager.getSaTokenDao()).thenReturn(dao);
        // StpLogic.getLoginIdByToken → getLoginIdNotHandle → getSaTokenDao().get(拼接后的 token key)。
        // 仅对含 mock-token 的 key 返回 loginId（getLoginId 读取登录态用）；
        // login 生成新 token 时的冲突检查也用 get()，若一律返回 loginId 会误判"已存在"导致 token 生成死循环，
        // 故其余 key 默认返回 null（token 不冲突，可正常生成）
        lenient().when(dao.get(contains("mock-token"))).thenReturn(String.valueOf(loginId));

        return new SaTokenLoginScope(manager);
    }

    /**
     * 登录 mock 作用域：close() 不抛受检异常，可直接用于 try-with-resources
     */
    public static final class SaTokenLoginScope implements AutoCloseable {

        private final MockedStatic<SaManager> manager;

        private SaTokenLoginScope(MockedStatic<SaManager> manager) {
            this.manager = manager;
        }

        @Override
        public void close() {
            manager.close();
        }
    }
}
