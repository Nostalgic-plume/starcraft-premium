package com.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.BusinessException;
import com.shop.common.StpUserUtil;
import com.shop.constant.ProductConstants;
import com.shop.mapper.FavoriteMapper;
import com.shop.mapper.ProductMapper;
import com.shop.model.entity.FavoriteDO;
import com.shop.model.entity.ProductDO;
import com.shop.model.vo.ProductVO;
import com.shop.service.FavoriteService;
import com.shop.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品收藏服务实现
 * <p>
 * 收藏列表不存商品快照，实时回查商品表：商品删除 / 下架后自动从列表消失，
 * 避免「收藏里还能看到已下架商品」的死数据（与订单快照的取舍相反，收藏是可重建的弱数据）。
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {

    private final FavoriteMapper favoriteMapper;
    private final ProductMapper productMapper;
    private final ProductService productService;

    @Override
    public List<ProductVO> list() {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        List<FavoriteDO> favorites = favoriteMapper.selectList(new LambdaQueryWrapper<FavoriteDO>()
                .eq(FavoriteDO::getUserId, userId)
                .orderByDesc(FavoriteDO::getCreateTime)
                .orderByDesc(FavoriteDO::getId));
        if (favorites.isEmpty()) {
            return List.of();
        }

        List<ProductVO> result = new ArrayList<>(favorites.size());
        for (FavoriteDO favorite : favorites) {
            try {
                ProductVO product = productService.getDetail(favorite.getProductId());
                // 只展示上架商品：下架 / 待审核的收藏项不可购买，留在列表里只会误导用户
                if (product != null && product.getStatus() != null
                        && product.getStatus() == ProductConstants.STATUS_ON_SHELF) {
                    result.add(product);
                }
            } catch (Exception e) {
                // 商品已删除：单条跳过，不中断整个收藏列表
                log.debug("收藏商品不可展示，已跳过, productId: {}", favorite.getProductId());
            }
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Long productId) {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();

        ProductDO product = productId == null ? null : productMapper.selectById(productId);
        if (product == null || product.getStatus() == null
                || product.getStatus() != ProductConstants.STATUS_ON_SHELF) {
            throw new BusinessException("商品不存在或已下架");
        }

        Long exists = favoriteMapper.selectCount(new LambdaQueryWrapper<FavoriteDO>()
                .eq(FavoriteDO::getUserId, userId)
                .eq(FavoriteDO::getProductId, productId));
        if (exists > 0) {
            return; // 幂等：重复收藏直接成功
        }

        FavoriteDO favorite = new FavoriteDO();
        favorite.setUserId(userId);
        favorite.setProductId(productId);
        try {
            favoriteMapper.insert(favorite);
        } catch (DuplicateKeyException e) {
            // 并发下两个请求同时通过上面的存在性检查，唯一索引兜底：视为已收藏
            log.debug("重复收藏已忽略, userId: {}, productId: {}", userId, productId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long productId) {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        if (productId == null) {
            return;
        }
        favoriteMapper.delete(new LambdaQueryWrapper<FavoriteDO>()
                .eq(FavoriteDO::getUserId, userId)
                .eq(FavoriteDO::getProductId, productId));
        log.info("取消收藏, userId: {}, productId: {}", userId, productId);
    }

    @Override
    public boolean isFavorite(Long productId) {
        if (productId == null) {
            return false;
        }
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        return favoriteMapper.selectCount(new LambdaQueryWrapper<FavoriteDO>()
                .eq(FavoriteDO::getUserId, userId)
                .eq(FavoriteDO::getProductId, productId)) > 0;
    }

    @Override
    public long count() {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        return favoriteMapper.selectCount(new LambdaQueryWrapper<FavoriteDO>()
                .eq(FavoriteDO::getUserId, userId));
    }
}
