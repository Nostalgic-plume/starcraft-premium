package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.shop.common.Result;
import com.shop.model.vo.DashboardAllVO;
import com.shop.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据看板控制器
 * 提供离线数仓各维度指标聚合数据，供 DataDashboard.vue 前端调用
 *
 * @author 陈
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 获取看板全量数据
     * 聚合 9 个数仓维度，返回统一 Result 包装
     */
    @SaCheckLogin(type ="user")
    @GetMapping("/all")
    public Result<DashboardAllVO> all() {
        log.info("查询数据看板全量数据");
        DashboardAllVO data = dashboardService.getDashboardAll();
        return Result.success(data);
    }
}
