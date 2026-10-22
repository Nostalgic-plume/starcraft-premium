package com.shop.service;

import com.shop.model.dto.ProductDTO;
import com.shop.model.query.ProductPageQuery;
import com.shop.model.vo.ProductVO;
import com.shop.common.PageResult;

/**
 * 商品服务
 * 处理 SPU/SKU 管理，包含商品 CRUD、上下架、分页查询
 *
 * @author shop
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
     * 编辑商品（SPU + SKU）：更新商品基本信息，并按 id 对 SKU 列表做「增 / 改 / 删」diff
     * <p>
     * 归属校验：商家登录时只能编辑本店商品（shop_id 隔离），管理员登录时放行。
     * 变更后主动失效商品详情缓存并同步 RAG 索引；SKU 库存有变动时额外失效库存缓存。
     * </p>
     * <p>
     * SKU 三态约定：带 id 且属于本商品 → 更新；不带 id → 新增；库中存在但提交列表未含 → 逻辑删除。
     * </p>
     *
     * @param id  商品 ID
     * @param dto 商品信息（字段同新增，SKU 列表为完整列表）
     * @throws BusinessException 商品不存在、无权操作该商品、分类不存在或非叶子分类、品牌不存在、SKU 不属于该商品
     */
    void updateProduct(Long id, ProductDTO dto);

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

    /**
     * 商家调整 SKU 库存（更新 MySQL 后按 Cache Aside 删除库存缓存与商品详情缓存，
     * 下次预扣/查询自动加载新值，保证 Redis 与 DB 一致）
     *
     * @param skuId SKU ID
     * @param stock 新库存（>=0）
     * @throws BusinessException SKU 不存在、无权操作该商品、库存为负
     */
    void updateSkuStock(Long skuId, Integer stock);

    /**
     * 删除商品（逻辑删除）
     * <p>
     * 仅管理员入口使用。删除后主动失效商品详情缓存，并从 Qdrant / ES 索引移除，
     * 避免「商品已删但检索仍能搜到」的幽灵数据；历史订单因存有商品快照不受影响。
     * SKU 不做级联删除：SKU 无独立对外入口，商品不可见后其 SKU 自然无法被访问。
     *
     * @param id 商品 ID
     * @throws BusinessException 商品不存在
     */
    void deleteProduct(Long id);
}
