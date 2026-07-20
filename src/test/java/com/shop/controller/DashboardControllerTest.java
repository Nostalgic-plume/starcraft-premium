package com.shop.controller;

import com.shop.common.Result;
import com.shop.config.MockMyBatisConfig;
import org.springframework.context.annotation.Import;
import com.shop.model.vo.DashboardAllVO;
import com.shop.service.DashboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * DashboardController 单元测试
 * 覆盖数据看板全量数据查询接口
 */
@WebMvcTest(DashboardController.class)
@Import(MockMyBatisConfig.class)
@DisplayName("DashboardController - 数据看板接口")
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DashboardService dashboardService;

    @Test
    @DisplayName("获取数据看板全量数据")
    void all_ShouldReturnDashboardData() throws Exception {
        DashboardAllVO vo = new DashboardAllVO();
        vo.setOverview(new DashboardAllVO.OverviewVO());

        given(dashboardService.getDashboardAll()).willReturn(vo);

        mockMvc.perform(get("/api/dashboard/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.msg", is("操作成功")))
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    @DisplayName("获取看板数据：服务层返回 null 时仍返回成功")
    void all_ShouldReturnOk_WhenServiceReturnsNull() throws Exception {
        given(dashboardService.getDashboardAll()).willReturn(null);

        mockMvc.perform(get("/api/dashboard/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
