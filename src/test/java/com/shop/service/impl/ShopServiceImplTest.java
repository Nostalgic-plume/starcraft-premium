package com.shop.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.SaTokenMockUtil;
import com.shop.common.BusinessException;
import com.shop.mapper.ShopMapper;
import com.shop.mapper.StatsMapper;
import com.shop.model.dto.ShopLoginDTO;
import com.shop.model.entity.ShopDO;
import com.shop.model.vo.DashboardStatsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ShopService 单元测试
 * 覆盖商家登录、数据看板、当前店铺 ID 查询
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShopService - 商家服务")
class ShopServiceImplTest {

    @Mock
    private ShopMapper shopMapper;

    @Mock
    private StatsMapper statsMapper;

    @InjectMocks
    private ShopServiceImpl shopService;

    private ShopLoginDTO loginDTO;
    private ShopDO shop;

    @BeforeEach
    void setUp() {
        loginDTO = new ShopLoginDTO();
        loginDTO.setUsername("shop001");
        loginDTO.setPassword("123456");

        shop = new ShopDO();
        shop.setId(10L);
        shop.setUsername("shop001");
        shop.setPassword("$2a$10$hashhashhashashashashashashashashashah");
        shop.setShopName("测试店铺");
    }

    // ==================== 商家登录 ====================

    @Nested
    @DisplayName("商家登录 - login()")
    class LoginTest {

        @Test
        @DisplayName("登录成功：返回 token、shopId、shopName")
        void login_ShouldReturnToken_WhenCredentialsValid() {
            when(shopMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(shop);
            try (MockedStatic<DigestUtil> digestUtil = mockStatic(DigestUtil.class);
                 var login = SaTokenMockUtil.userLogin(10L)) {

                digestUtil.when(() -> DigestUtil.bcryptCheck(anyString(), anyString())).thenReturn(true);

                Map<String, Object> result = shopService.login(loginDTO);

                // 三端会话重构后登录走 StpShopUtil.INSTANCE.login/getTokenValue 实例方法，token 来自 mock 请求上下文
                assertEquals("mock-token", result.get("token"));
                assertEquals(10L, result.get("shopId"));
                assertEquals("测试店铺", result.get("shopName"));
            }
        }

        @Test
        @DisplayName("登录失败：账号不存在")
        void login_ShouldThrow_WhenShopNotFound() {
            when(shopMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> shopService.login(loginDTO));
            assertEquals("店铺账号不存在", ex.getMessage());
        }

        @Test
        @DisplayName("登录失败：密码错误")
        void login_ShouldThrow_WhenPasswordWrong() {
            when(shopMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(shop);
            try (MockedStatic<DigestUtil> digestUtil = mockStatic(DigestUtil.class)) {
                digestUtil.when(() -> DigestUtil.bcryptCheck(anyString(), anyString())).thenReturn(false);

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> shopService.login(loginDTO));
                assertEquals("密码错误", ex.getMessage());
            }
        }
    }

    // ==================== 商家看板 ====================

    @Nested
    @DisplayName("商家数据看板 - getDashboard()")
    class GetDashboardTest {

        @Test
        @DisplayName("成功获取商家看板数据")
        void getDashboard_ShouldReturnStats() {
            DashboardStatsVO statsVO = new DashboardStatsVO();

            try (var login = SaTokenMockUtil.userLogin(10L)) {
                when(statsMapper.selectMerchantDashboard(10L)).thenReturn(statsVO);

                DashboardStatsVO result = shopService.getDashboard();

                assertSame(statsVO, result);
            }
        }
    }

    // ==================== 获取当前店铺 ID ====================

    @Nested
    @DisplayName("获取当前店铺 ID - getCurrentShopId()")
    class GetCurrentShopIdTest {

        @Test
        @DisplayName("成功获取当前登录商家 ID")
        void getCurrentShopId_ShouldReturnId_WhenLoggedIn() {
            try (var login = SaTokenMockUtil.userLogin(10L)) {

                Long result = shopService.getCurrentShopId();

                assertEquals(10L, result);
            }
        }

        @Test
        @DisplayName("获取失败：未登录")
        void getCurrentShopId_ShouldThrow_WhenNotLoggedIn() {
            try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
                stpUtil.when(StpUtil::getLoginIdAsLong).thenThrow(new RuntimeException("未登录"));

                BusinessException ex = assertThrows(BusinessException.class,
                        () -> shopService.getCurrentShopId());
                assertEquals("未登录，请先登录", ex.getMessage());
            }
        }
    }
}
