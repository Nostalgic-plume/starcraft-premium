package com.shop.rec.mall.mapper;

import com.shop.rec.mall.model.MallCategoryDO;
import com.shop.rec.mall.model.MallProductDO;
import com.shop.rec.mall.model.MallSkuStatDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商城商品数据只读 Mapper
 *
 * <p>
 * 供 M-04 维护商品维度映射、可售状态缓存与类目候选索引。
 * <b>本 Mapper 只执行查询，不对商城业务库做任何写操作</b>（对应概要设计 3.2 的只读约定）。
 *
 * <p>
 * 绑定商城业务库只读数据源（见 {@code MallDataSourceConfig}），与推荐库 Mapper 分属不同
 * SqlSessionFactory。
 *
 * @author shop
 */
@Mapper
public interface MallProductMapper {

    /**
     * 按主键区间分批查询商品（全量加载用）
     *
     * <p>对应详细设计 3.4「全量加载：每日低峰期分批（每批 500 条）读取商品数据」，
     * 按主键递增翻页可避免大偏移量分页的性能问题。
     *
     * @param lastId 上一批的最大主键，首批传 0
     * @param limit  每批条数
     * @return 商品列表（按主键升序）
     */
    @Select("SELECT id, name, category_id, brand_id, shop_id, main_image, status "
            + "FROM product WHERE deleted = 0 AND id > #{lastId} ORDER BY id LIMIT #{limit}")
    List<MallProductDO> selectBatchAfterId(@Param("lastId") long lastId, @Param("limit") int limit);

    /**
     * 查询全部启用类目
     *
     * <p>用于建立一级 / 二级类目关系；类目数据量小，一次性加载。
     *
     * @return 类目列表
     */
    @Select("SELECT id, name, parent_id FROM category WHERE deleted = 0 AND status = 0")
    List<MallCategoryDO> selectAllCategories();

    /**
     * 批量查询商品的库存与价格统计
     *
     * <p>商品总库存为全部规格库存合计（对应 item-change 报文的 totalStock 口径）；
     * 最低价用于榜单展示。按商品标识分组，只返回传入的商品。
     *
     * @param productIds 商品标识列表（非空）
     * @return 每个商品的总库存与最低价
     */
    @Select("<script>"
            + "SELECT product_id, SUM(stock) AS total_stock, MIN(price) AS min_price "
            + "FROM sku WHERE deleted = 0 AND product_id IN "
            + "<foreach collection='productIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY product_id"
            + "</script>")
    List<MallSkuStatDO> selectSkuStats(@Param("productIds") List<Long> productIds);

    /**
     * 查询指定商品的更新信息（对账用）
     *
     * <p>对应详细设计 3.4「对账修正：每 10 分钟比对商品更新时间，修正因消息丢失导致的偏差」。
     *
     * @param productIds 商品标识列表（非空）
     * @return 商品标识与更新时间
     */
    @Select("<script>"
            + "SELECT id, update_time, status, category_id, brand_id, shop_id "
            + "FROM product WHERE deleted = 0 AND id IN "
            + "<foreach collection='productIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<MallProductDO> selectByIds(@Param("productIds") List<Long> productIds);

    /**
     * 查询全部可售商品（上架且有库存）
     *
     * <p>用于重建类目候选索引与对账：只返回「上架 + 库存 > 0」的商品，
     * 与可售判定口径一致。商品规模在演示量级，一次拉取即可。
     *
     * @param limit 返回条数上限；传 0 表示不限制
     * @return 可售商品列表
     */
    @Select("<script>"
            + "SELECT p.id, p.name, p.category_id, p.brand_id, p.shop_id, p.main_image, p.status "
            + "FROM product p WHERE p.deleted = 0 AND p.status = 1 "
            + "AND EXISTS (SELECT 1 FROM sku s WHERE s.product_id = p.id AND s.deleted = 0 AND s.stock > 0) "
            + "<if test='limit > 0'>LIMIT #{limit}</if>"
            + "</script>")
    List<MallProductDO> selectSellableProducts(@Param("limit") int limit);
}
