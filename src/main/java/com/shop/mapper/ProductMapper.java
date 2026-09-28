package com.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.model.entity.ProductDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商品 Mapper
 * 提供商品表（product）的基础 CRUD + FULLTEXT 全文检索
 *
 * @author shop
 */
public interface ProductMapper extends BaseMapper<ProductDO> {

    /**
     * MySQL FULLTEXT 全文检索（ngram 分词器）
     * MATCH AGAINST IN NATURAL LANGUAGE MODE 返回按相关性降序排列的商品
     */
    @Select("SELECT * FROM product WHERE deleted = 0 AND status = 1 " +
            "AND MATCH(name, description) AGAINST(#{query} IN NATURAL LANGUAGE MODE) > 0 " +
            "ORDER BY MATCH(name, description) AGAINST(#{query} IN NATURAL LANGUAGE MODE) DESC " +
            "LIMIT #{limit}")
    List<ProductDO> searchByKeyword(@Param("query") String query, @Param("limit") int limit);

    /**
     * 重建 FULLTEXT 索引（OPTIMIZE TABLE 会重建整个表及其所有索引，包括 FULLTEXT）
     * 在批量数据变更或全量重建 Qdrant 索引后调用，确保 FULLTEXT 索引处于最优状态
     */
    @Select("OPTIMIZE TABLE product")
    void optimizeFulltextIndex();

    /**
     * 测试 FULLTEXT 索引是否正常工作
     * 返回匹配行数 > 0 表示索引可用
     */
    @Select("SELECT COUNT(*) FROM product WHERE deleted = 0 AND status = 1 " +
            "AND MATCH(name, description) AGAINST(#{query} IN NATURAL LANGUAGE MODE)")
    int testFulltextIndex(@Param("query") String query);
}
