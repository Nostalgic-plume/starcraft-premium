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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopServiceImpl implements ShopService {

    private final ShopMapper shopMapper;
    private final StatsMapper statsMapper;

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

    @Override
    public DashboardStatsVO getDashboard() {
        return statsMapper.selectMerchantDashboard(getCurrentShopId());
    }

    @Override
    public Long getCurrentShopId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            throw new BusinessException("未登录，请先登录");
        }
    }

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
