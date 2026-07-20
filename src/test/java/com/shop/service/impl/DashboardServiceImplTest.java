package com.shop.service.impl;

import com.shop.mapper.DwOverviewMapper;
import com.shop.model.vo.DashboardAllVO;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DashboardService 单元测试
 * 覆盖数据看板聚合查询，重点验证各查询模块的异常隔离和 null-safe fallback
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardService - 数据看板服务")
class DashboardServiceImplTest {

    @Mock
    private DwOverviewMapper dwOverviewMapper;

    @InjectMocks
    private DashboardServiceImpl dashboardService;

    // ==================== getDashboardAll ====================

    @Nested
    @DisplayName("聚合看板 - getDashboardAll()")
    class GetDashboardAllTest {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("全部查询成功：返回完整看板数据")
        void getDashboardAll_ShouldReturnAllData_WhenAllQueriesSucceed() {
            DashboardAllVO.OverviewVO overview = new DashboardAllVO.OverviewVO();
            DashboardAllVO.GmvDailyVO gmvDaily = new DashboardAllVO.GmvDailyVO();
            DashboardAllVO.OrderStatsVO orderStats = new DashboardAllVO.OrderStatsVO();
            when(dwOverviewMapper.selectOverview()).thenReturn(overview);
            when(dwOverviewMapper.selectGmvDaily()).thenReturn(gmvDaily);
            when(dwOverviewMapper.selectOrderStats()).thenReturn(orderStats);
            when(dwOverviewMapper.selectCategorySales()).thenReturn(List.of());
            when(dwOverviewMapper.selectProductTop()).thenReturn(List.of());
            when(dwOverviewMapper.selectWeeklyTrend()).thenReturn(List.of());
            when(dwOverviewMapper.selectUserActive()).thenReturn(mock(DashboardAllVO.UserActiveVO.class));
            when(dwOverviewMapper.selectUserNewRetain()).thenReturn(mock(DashboardAllVO.UserNewRetainVO.class));
            when(dwOverviewMapper.selectShopDaily()).thenReturn(mock(DashboardAllVO.ShopDailyVO.class));
            when(dwOverviewMapper.selectGmvTrend()).thenReturn(List.of());
            when(dwOverviewMapper.selectOrderTrend()).thenReturn(List.of());

            DashboardAllVO vo = dashboardService.getDashboardAll();

            assertSame(overview, vo.getOverview());
            assertSame(gmvDaily, vo.getGmvDaily());
            assertSame(orderStats, vo.getOrderStats());
            assertNotNull(vo.getCategorySales());
            assertNotNull(vo.getProductTop());
            assertNotNull(vo.getWeeklyTrend());
            assertNotNull(vo.getUserActive());
            assertNotNull(vo.getUserNewRetain());
            assertNotNull(vo.getShopDaily());
            assertNotNull(vo.getGmvTrend());
            assertNotNull(vo.getOrderTrend());

            verify(dwOverviewMapper, times(1)).selectOverview();
            verify(dwOverviewMapper, times(1)).selectGmvTrend();
        }

        @Test
        @DisplayName("部分查询异常：失败的模块返回空对象，其余正常")
        void getDashboardAll_ShouldFallback_WhenSomeQueriesFail() {
            // 前3个查询（有 null-safe fallback）抛异常
            when(dwOverviewMapper.selectOverview()).thenThrow(new RuntimeException("DB error"));
            when(dwOverviewMapper.selectGmvDaily()).thenThrow(new RuntimeException("DB error"));
            when(dwOverviewMapper.selectOrderStats()).thenThrow(new RuntimeException("DB error"));
            // 其余查询正常返回
            when(dwOverviewMapper.selectCategorySales()).thenThrow(new RuntimeException("DB error"));
            when(dwOverviewMapper.selectProductTop()).thenThrow(new RuntimeException("DB error"));
            when(dwOverviewMapper.selectWeeklyTrend()).thenThrow(new RuntimeException("DB error"));
            when(dwOverviewMapper.selectUserActive()).thenThrow(new RuntimeException("DB error"));
            when(dwOverviewMapper.selectUserNewRetain()).thenThrow(new RuntimeException("DB error"));
            when(dwOverviewMapper.selectShopDaily()).thenThrow(new RuntimeException("DB error"));
            when(dwOverviewMapper.selectGmvTrend()).thenReturn(List.of());
            when(dwOverviewMapper.selectOrderTrend()).thenReturn(List.of());

            DashboardAllVO vo = dashboardService.getDashboardAll();

            // 有 null-safe fallback → 默认空对象
            assertNotNull(vo.getOverview());
            assertNotNull(vo.getGmvDaily());
            assertNotNull(vo.getOrderStats());
            assertNotNull(vo.getUserActive());
            assertNotNull(vo.getUserNewRetain());
            assertNotNull(vo.getShopDaily());

            // 无 fallback → null
            assertNull(vo.getCategorySales());
            assertNull(vo.getProductTop());
            assertNull(vo.getWeeklyTrend());

            // 成功查询
            assertNotNull(vo.getGmvTrend());
            assertNotNull(vo.getOrderTrend());
        }

        @Test
        @DisplayName("全部查询异常：所有模块返回空对象/null")
        void getDashboardAll_ShouldReturnEmpty_WhenAllQueriesFail() {
            when(dwOverviewMapper.selectOverview()).thenThrow(new RuntimeException("DB err"));
            when(dwOverviewMapper.selectGmvDaily()).thenThrow(new RuntimeException("DB err"));
            when(dwOverviewMapper.selectOrderStats()).thenThrow(new RuntimeException("DB err"));
            when(dwOverviewMapper.selectCategorySales()).thenThrow(new RuntimeException("DB err"));
            when(dwOverviewMapper.selectProductTop()).thenThrow(new RuntimeException("DB err"));
            when(dwOverviewMapper.selectWeeklyTrend()).thenThrow(new RuntimeException("DB err"));
            when(dwOverviewMapper.selectUserActive()).thenThrow(new RuntimeException("DB err"));
            when(dwOverviewMapper.selectUserNewRetain()).thenThrow(new RuntimeException("DB err"));
            when(dwOverviewMapper.selectShopDaily()).thenThrow(new RuntimeException("DB err"));
            when(dwOverviewMapper.selectGmvTrend()).thenThrow(new RuntimeException("DB err"));
            when(dwOverviewMapper.selectOrderTrend()).thenThrow(new RuntimeException("DB err"));

            DashboardAllVO vo = dashboardService.getDashboardAll();

            // 有 null-safe fallback 的字段
            assertNotNull(vo.getOverview());
            assertNotNull(vo.getGmvDaily());
            assertNotNull(vo.getOrderStats());
            assertNotNull(vo.getUserActive());
            assertNotNull(vo.getUserNewRetain());
            assertNotNull(vo.getShopDaily());

            // 无 fallback 的聚合列表
            assertNull(vo.getCategorySales());
            assertNull(vo.getProductTop());
            assertNull(vo.getWeeklyTrend());
            assertNull(vo.getGmvTrend());
            assertNull(vo.getOrderTrend());
        }
    }
}
