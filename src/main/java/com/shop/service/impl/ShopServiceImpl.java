package com.shop.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.BusinessException;
import com.shop.mapper.ShopMapper;
import com.shop.mapper.StatsMapper;
import com.shop.model.dto.ShopLoginDTO;
import com.shop.model.entity.ShopDO;
import com.shop.model.vo.DashboardStatsVO;
import com.shop.service.ShopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 商家服务实现
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopServiceImpl implements ShopService {

    private final ShopMapper shopMapper;
    private final StatsMapper statsMapper;

    /**
     * 商家登录：校验店铺账号存在性、BCrypt 密码与账号状态后，以 Sa-Token「shop」设备类型建立登录会话
     *
     * @param dto 登录请求参数（username 店铺用户名、password 明文密码，均必填）
     * @return 登录成功后返回 token、shopId、shopName 组成的 Map
     * @throws BusinessException 店铺账号不存在、密码错误、店铺账号已被停用
     */
    @Override
    public Map<String, Object> login(ShopLoginDTO dto) {
        ShopDO shop = shopMapper.selectOne(
                new LambdaQueryWrapper<ShopDO>().eq(ShopDO::getUsername, dto.getUsername()));
        if (shop == null) {
            throw new BusinessException("店铺账号不存在");
        }
        if (!DigestUtil.bcryptCheck(dto.getPassword(), shop.getPassword())) {
            throw new BusinessException("密码错误");
        }
        // 账号状态校验
        if (shop.getStatus() != null && shop.getStatus() != 0) {
            throw new BusinessException("店铺账号已被停用");
        }
        StpUtil.login(shop.getId(), "shop");
        log.info("商家登录成功, shopId: {}, shopName: {}", shop.getId(), shop.getShopName());
        return Map.of(
                "token", StpUtil.getTokenValue(),
                "shopId", shop.getId(),
                "shopName", shop.getShopName());
    }

    /**
     * 查询当前登录商家的控制台统计概览（销售额/订单数等），shop_id 取自当前会话
     *
     * @return 商家看板统计 DashboardStatsVO
     */
    @Override
    public DashboardStatsVO getDashboard() {
        return statsMapper.selectMerchantDashboard(getCurrentShopId());
    }

    /**
     * 获取当前登录商家的 shop_id
     *
     * @return 当前商家ID（Long）
     * @throws BusinessException 未登录，请先登录
     */
    @Override
    public Long getCurrentShopId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            throw new BusinessException("未登录，请先登录");
        }
    }

    /**
     * 查询当前登录商家的店铺信息，可空字段（logo/简介/联系人等）兜底为空串，避免 Map.of 拒绝 null 值
     *
     * @return 店铺信息 Map（shopId、username、shopName、shopLogo、shopDesc、contactName、contactPhone）
     * @throws BusinessException 店铺不存在
     */
    @Override
    public Map<String, Object> getCurrentShopInfo() {
        ShopDO shop = shopMapper.selectById(getCurrentShopId());
        if (shop == null) {
            throw new BusinessException("店铺不存在");
        }
        // Map.of 不允许 null 值，可空字段用三元兜底
        return Map.of(
                "shopId", shop.getId(),
                "username", shop.getUsername(),
                "shopName", shop.getShopName(),
                "shopLogo", shop.getShopLogo() == null ? "" : shop.getShopLogo(),
                "shopDesc", shop.getShopDesc() == null ? "" : shop.getShopDesc(),
                "contactName", shop.getContactName() == null ? "" : shop.getContactName(),
                "contactPhone", shop.getContactPhone() == null ? "" : shop.getContactPhone());
    }
}
