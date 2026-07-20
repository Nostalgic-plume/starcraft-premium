package com.shop.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.BusinessException;
import com.shop.mapper.CartMapper;
import com.shop.mapper.ProductMapper;
import com.shop.mapper.SkuMapper;
import com.shop.model.dto.CartDTO;
import com.shop.model.entity.CartDO;
import com.shop.model.entity.ProductDO;
import com.shop.model.entity.SkuDO;
import com.shop.model.vo.CartVO;
import com.shop.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 购物车服务实现
 * 操作粒度为 SKU 级别，自动回填商品信息快照
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final CartMapper cartMapper;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addItem(CartDTO dto) {
        long userId = StpUtil.getLoginIdAsLong();

        // 校验 SKU
        SkuDO sku = skuMapper.selectById(dto.getSkuId());
        if (sku == null) {
            throw new BusinessException("商品不存在");
        }

        // 检查是否已在购物车
        CartDO exist = cartMapper.selectOne(
                new LambdaQueryWrapper<CartDO>()
                        .eq(CartDO::getUserId, userId)
                        .eq(CartDO::getSkuId, dto.getSkuId()));

        if (exist != null) {
            // 已存在则增加数量
            int newQuantity = exist.getQuantity() + dto.getQuantity();
            if (newQuantity > sku.getStock()) {
                throw new BusinessException("库存不足，当前库存: " + sku.getStock());
            }
            exist.setQuantity(newQuantity);
            cartMapper.updateById(exist);
        } else {
            // 新增
            if (dto.getQuantity() > sku.getStock()) {
                throw new BusinessException("库存不足，当前库存: " + sku.getStock());
            }
            CartDO cart = new CartDO();
            cart.setUserId(userId);
            cart.setProductId(sku.getProductId());
            cart.setSkuId(dto.getSkuId());
            cart.setQuantity(dto.getQuantity());
            cart.setChecked(1);
            cartMapper.insert(cart);
        }
        log.info("购物车添加成功, userId: {}, skuId: {}, quantity: {}", userId, dto.getSkuId(), dto.getQuantity());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateQuantity(Long skuId, Integer quantity) {
        long userId = StpUtil.getLoginIdAsLong();
        CartDO cart = getCartItem(userId, skuId);
        // 校验库存
        SkuDO sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new BusinessException("商品不存在或已下架");
        }
        if (quantity > sku.getStock()) {
            throw new BusinessException("库存不足，当前库存: " + sku.getStock());
        }
        cart.setQuantity(quantity);
        cartMapper.updateById(cart);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeItem(Long skuId) {
        long userId = StpUtil.getLoginIdAsLong();
        cartMapper.delete(
                new LambdaQueryWrapper<CartDO>()
                        .eq(CartDO::getUserId, userId)
                        .eq(CartDO::getSkuId, skuId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkItem(Long skuId, Boolean checked) {
        long userId = StpUtil.getLoginIdAsLong();
        CartDO cart = getCartItem(userId, skuId);
        cart.setChecked(checked ? 1 : 0);
        cartMapper.updateById(cart);
    }

    @Override
    public List<CartVO> list() {
        long userId = StpUtil.getLoginIdAsLong();

        List<CartDO> cartList = cartMapper.selectList(
                new LambdaQueryWrapper<CartDO>()
                        .eq(CartDO::getUserId, userId)
                        .eq(CartDO::getIsOrdered, 0) // 只取未下单的购物车商品
                        .orderByDesc(CartDO::getCreateTime));

        return cartList.stream().map(cart -> {
            SkuDO sku = skuMapper.selectById(cart.getSkuId());
            CartVO vo = new CartVO();
            BeanUtil.copyProperties(cart, vo);

            if (sku != null) {
                vo.setProductId(sku.getProductId());
                vo.setSkuName(sku.getName());
                vo.setPrice(sku.getPrice());
                vo.setStock(sku.getStock());
                vo.setSubtotal(sku.getPrice().multiply(BigDecimal.valueOf(cart.getQuantity())));
                vo.setValid(sku.getStock() >= cart.getQuantity());
                // 回填商品名称和主图
                ProductDO product = productMapper.selectById(sku.getProductId());
                if (product != null) {
                    vo.setProductName(product.getName());
                    vo.setProductImage(product.getMainImage());
                }
            } else {
                vo.setValid(false);
            }

            vo.setChecked(cart.getChecked() == 1);
            return vo;
        }).collect(Collectors.toList());
    }

    private CartDO getCartItem(long userId, Long skuId) {
        CartDO cart = cartMapper.selectOne(
                new LambdaQueryWrapper<CartDO>()
                        .eq(CartDO::getUserId, userId)
                        .eq(CartDO::getSkuId, skuId));
        if (cart == null) {
            throw new BusinessException("购物车中未找到该商品");
        }
        return cart;
    }
}
