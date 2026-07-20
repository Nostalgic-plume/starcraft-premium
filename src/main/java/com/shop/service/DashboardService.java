package com.shop.service;

import com.shop.model.vo.DashboardAllVO;

/**
 * 数据看板查询服务
 * 从离线数仓 shop_ads 库中获取各指标数据
 *
 * @author shop
 */
public interface DashboardService {

    /**
     * 获取看板全量数据
     * 聚合数仓 9 张 ads_* 表的最新数据
     */
    DashboardAllVO getDashboardAll();
}
