package com.shop.service;

import com.shop.model.dto.ProductDTO;
import com.shop.model.query.ProductPageQuery;
import com.shop.model.vo.ProductVO;
import com.shop.common.PageResult;

/**
 * 商品服务
 * 处理 SPU/SKU 管理，包含商品 CRUD、上下架、分页查询
 */
public interface ProductService {

    /**
     * 新增商品（含 SKU 列表）
     * 校验分类是否存在，创建 SPU 及对应 SKU 数据
     *
     * @param dto 商品信息（名称、分类、SKU 列表等）
     * @return 新建商品的 ID
     * @throws BusinessException 分类不存在时抛出
     */
    Long addProduct(ProductDTO dto);

    /**
     * 分页查询商品列表
     * 支持按分类、关键字筛选，默认按创建时间倒序
     *
     * @param query 分页参数 + 筛选条件
     * @return 分页结果，含商品列表和分页元数据
     */
    PageResult<ProductVO> page(ProductPageQuery query);

    /**
     * 商家商品分页（shop_id 从 StpUtil 自动获取，无需前端传入）
     *
     * @param query 分页参数 + 筛选条件
     * @return 分页结果
     */
    PageResult<ProductVO> merchantPage(ProductPageQuery query);

    /**
     * 获取商品详情（含 SKU 列表、价格区间、库存总量）
     *
     * @param id 商品 ID
     * @return 商品详情 VO
     * @throws BusinessException 商品不存在时抛出
     */
    ProductVO getDetail(Long id);

    /**
     * 更新商品上下架状态
     *
     * @param id     商品 ID
     * @param status 目标状态（ProductConstants 中定义）
     * @throws BusinessException 商品不存在时抛出
     */
    void updateStatus(Long id, Integer status);
}
