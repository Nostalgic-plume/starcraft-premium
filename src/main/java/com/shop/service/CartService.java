package com.shop.service;

import com.shop.model.dto.CartDTO;
import com.shop.model.vo.CartVO;

import java.util.List;

/**
 * 购物车服务
 * 管理用户购物车的增删改查，包含库存校验
 */
public interface CartService {

    /**
     * 添加商品到购物车
     * 已存在相同 SKU 时增加数量，不存在时新增记录
     *
     * @param dto SKU ID 和数量
     * @throws BusinessException 商品不存在或库存不足时抛出
     */
    void addItem(CartDTO dto);

    /**
     * 修改购物车中某 SKU 的数量
     *
     * @param skuId    SKU ID
     * @param quantity 新数量
     * @throws BusinessException 商品不存在或库存不足时抛出
     */
    void updateQuantity(Long skuId, Integer quantity);

    /**
     * 从购物车中删除某 SKU
     *
     * @param skuId SKU ID
     */
    void removeItem(Long skuId);

    /**
     * 选中/取消选中购物车中的某商品
     *
     * @param skuId  SKU ID
     * @param checked true 选中 / false 取消
     */
    void checkItem(Long skuId, Boolean checked);

    /**
     * 获取当前用户购物车列表
     * 自动回填商品名称、主图、价格，标记 SKU 是否有效
     *
     * @return 购物车 VO 列表，最新添加的在前
     */
    List<CartVO> list();
}
