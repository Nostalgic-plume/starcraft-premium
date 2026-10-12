package com.shop.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.common.StpUserUtil;
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
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final CartMapper cartMapper;
    private final ProductMapper productMapper;
    private final SkuMapper skuMapper;

    /**
     * 加入购物车：校验 SKU 存在性后，同一用户同一 SKU 已存在则累加数量（含库存校验），否则新增购物车记录
     *
     * @param dto 购物车操作参数（skuId、quantity，均必填）
     * @throws BusinessException 商品不存在、库存不足（当前库存 X）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addItem(CartDTO dto) {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();

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
            checkStock(newQuantity, sku);
            exist.setQuantity(newQuantity);
            cartMapper.updateById(exist);
        } else {
            // 新增
            checkStock(dto.getQuantity(), sku);
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

    /**
     * 修改购物车中某 SKU 的购买数量，变更前校验 SKU 存在与库存充足
     *
     * @param skuId    SKU ID，必填
     * @param quantity 新数量，必填且不能超过库存
     * @throws BusinessException 购物车中未找到该商品、商品不存在或已下架、库存不足
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateQuantity(Long skuId, Integer quantity) {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        CartDO cart = getCartItem(userId, skuId);
        // 校验库存
        SkuDO sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new BusinessException("商品不存在或已下架");
        }
        checkStock(quantity, sku);
        cart.setQuantity(quantity);
        cartMapper.updateById(cart);
    }

    /**
     * 从当前用户购物车删除指定 SKU 的购物车记录（MyBatis-Plus 逻辑删除）
     *
     * @param skuId SKU ID，必填
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeItem(Long skuId) {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        cartMapper.delete(
                new LambdaQueryWrapper<CartDO>()
                        .eq(CartDO::getUserId, userId)
                        .eq(CartDO::getSkuId, skuId));
    }

    /**
     * 勾选/取消勾选购物车中某 SKU 商品（checked 用于结算时是否选中）
     *
     * @param skuId   SKU ID，必填
     * @param checked true=勾选 false=取消勾选
     * @throws BusinessException 购物车中未找到该商品
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void checkItem(Long skuId, Boolean checked) {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        CartDO cart = getCartItem(userId, skuId);
        cart.setChecked(checked ? 1 : 0);
        cartMapper.updateById(cart);
    }

    /**
     * 查询当前用户购物车列表（仅未下单商品，按加入时间倒序），逐项回填 SKU 名称/价格/库存/小计与商品名称/主图，
     * 并标记勾选状态与有效性（库存充足为 valid=true）
     *
     * @return 购物车列表 {@code List<CartVO>}；SKU 已删除或库存不足时对应项 valid=false
     */
    @Override
    public List<CartVO> list() {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();

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

    /**
     * 按用户与 SKU 查询购物车记录
     *
     * @param userId 用户ID，必填
     * @param skuId  SKU ID，必填
     * @return 购物车实体 CartDO
     * @throws BusinessException 购物车中未找到该商品
     */
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

    /** 校验库存是否充足 */
    private void checkStock(Integer quantity, SkuDO sku) {
        if (quantity > sku.getStock()) {
            throw new BusinessException("库存不足，当前库存: " + sku.getStock());
        }
    }
}
