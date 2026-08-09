package com.shop.service;

import com.shop.model.dto.ShopLoginDTO;
import com.shop.model.vo.DashboardStatsVO;

import java.util.Map;

/**
 * 商家服务
 * 处理商家登录、数据统计等业务逻辑
 */
public interface ShopService {

    /**
     * 商家登录（使用 shop 表独立账号）
     *
     * @param dto 登录参数（用户名、密码）
     * @return token、shopId、shopName
     */
    Map<String, Object> login(ShopLoginDTO dto);

    /**
     * 获取商家控制台概览数据
     *
     * @return 本店统计概览
     */
    DashboardStatsVO getDashboard();

    /**
     * 获取当前登录商家的 shopId
     *
     * @return shopId
     */
    Long getCurrentShopId();

    /**
     * 获取当前登录商家信息（不含密码，供商家端展示店铺名称/Logo）
     *
     * @return shopId、shopName、shopLogo、shopDesc、contactName、contactPhone
     */
    Map<String, Object> getCurrentShopInfo();
}
