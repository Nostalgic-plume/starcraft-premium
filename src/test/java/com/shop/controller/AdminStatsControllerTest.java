package com.shop.controller;

import com.shop.config.MockMyBatisConfig;
import com.shop.mapper.StatsMapper;
import org.springframework.context.annotation.Import;
import com.shop.model.vo.DashboardStatsVO;
import com.shop.model.vo.ProductSalesVO;
import com.shop.model.vo.SalesTrendVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AdminStatsController 单元测试
 * 覆盖控制台统计、销售趋势、商品销量排行、用户增长、订单分布接口
 * 注意：@SaCheckPermission 在 @WebMvcTest 中不生效，方法正常执行
 */
@WebMvcTest(AdminStatsController.class)
@Import(MockMyBatisConfig.class)
@DisplayName("AdminStatsController - 统计接口")
class AdminStatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StatsMapper statsMapper;

    @Test
    @DisplayName("控制台概览统计：返回所有指标")
    void dashboard_ShouldReturnAllStats() throws Exception {
        given(statsMapper.totalUsers()).willReturn(1000L);
        given(statsMapper.totalProducts()).willReturn(500L);
        given(statsMapper.totalOrders()).willReturn(3000L);
        given(statsMapper.totalSales()).willReturn(999999.00);
        given(statsMapper.pendingOrders()).willReturn(50L);

        mockMvc.perform(get("/api/admin/stats/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.totalUsers", is("1000")))
                .andExpect(jsonPath("$.data.totalProducts", is("500")))
                .andExpect(jsonPath("$.data.totalOrders", is("3000")))
                .andExpect(jsonPath("$.data.totalSales", is(999999.00)))
                .andExpect(jsonPath("$.data.pendingOrders", is("50")));
    }

    @Test
    @DisplayName("销售额趋势：默认 7 天")
    void salesTrend_ShouldReturn7Days() throws Exception {
        SalesTrendVO vo = new SalesTrendVO();
        vo.setDate("2026-07-11");
        vo.setAmount(new BigDecimal("5000.00"));

        given(statsMapper.salesTrendLast7Days()).willReturn(List.of(vo));

        mockMvc.perform(get("/api/admin/stats/sales-trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data[0].date", is("2026-07-11")))
                .andExpect(jsonPath("$.data[0].amount", is(5000.00)));
    }

    @Test
    @DisplayName("销售额趋势：days > 7 时查询 30 天数据")
    void salesTrend_ShouldReturn30Days_WhenDaysGt7() throws Exception {
        SalesTrendVO vo = new SalesTrendVO();
        vo.setDate("2026-06-18");
        vo.setAmount(new BigDecimal("8000.00"));

        given(statsMapper.salesTrendLast30Days()).willReturn(List.of(vo));

        mockMvc.perform(get("/api/admin/stats/sales-trend")
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].date", is("2026-06-18")));
    }

    @Test
    @DisplayName("商品销量排行 TOP10：返回排行榜")
    void productSales_ShouldReturnTop10() throws Exception {
        ProductSalesVO vo = new ProductSalesVO();
        vo.setName("畅销商品");
        vo.setTotalSales(1000L);

        given(statsMapper.topProductSales()).willReturn(List.of(vo));

        mockMvc.perform(get("/api/admin/stats/product-sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data[0].name", is("畅销商品")))
                .andExpect(jsonPath("$.data[0].totalSales", is("1000")));
    }

    @Test
    @DisplayName("用户增长趋势：返回近30天数据")
    void userGrowth_ShouldReturnLast30Days() throws Exception {
        SalesTrendVO vo = new SalesTrendVO();
        vo.setDate("2026-07-18");
        vo.setAmount(new BigDecimal("50.00"));

        given(statsMapper.userGrowthLast30Days()).willReturn(List.of(vo));

        mockMvc.perform(get("/api/admin/stats/user-growth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data[0].date", is("2026-07-18")));
    }

    @Test
    @DisplayName("订单状态分布：返回分布数据")
    void orderDistribution_ShouldReturnDistribution() throws Exception {
        Map<String, Object> dist = Map.of("status", 0, "count", 100);

        given(statsMapper.orderStatusDistribution()).willReturn(List.of(dist));

        mockMvc.perform(get("/api/admin/stats/order-distribution"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data[0].status", is(0)))
                .andExpect(jsonPath("$.data[0].count", is(100)));
    }

    @Test
    @DisplayName("商品销量排行：无数据时返回空数组")
    void productSales_ShouldReturnEmpty_WhenNoData() throws Exception {
        given(statsMapper.topProductSales()).willReturn(List.of());

        mockMvc.perform(get("/api/admin/stats/product-sales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }
}
