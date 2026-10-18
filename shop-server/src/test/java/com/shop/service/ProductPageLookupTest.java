package com.shop.service;

import com.shop.common.PageResult;
import com.shop.mapper.BrandMapper;
import com.shop.mapper.CategoryMapper;
import com.shop.model.entity.BrandDO;
import com.shop.model.entity.CategoryDO;
import com.shop.model.query.ProductPageQuery;
import com.shop.model.vo.ProductVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 商品分页整页预加载验证（code-review F6）
 * <p>
 * `convertToVO` 原先对每条商品各查 4 次（分类 / 品牌 / SKU / 销量），
 * pageSize 上限 200 时单次分页最多 800 次查询。改为整页批量预加载后，
 * 必须保证**回填字段与原语义一致**（查不到时不设值、无销量按 0），
 * 否则「查询变快但数据变错」，属更严重的回归。
 * <p>
 * 本测试断言的是字段完整性；SQL 次数由运行日志中的 Preparing 行人工核对
 * （同类表应只出现 1 条批量语句，而非 N 条单条语句）。
 */
@SpringBootTest
class ProductPageLookupTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private BrandMapper brandMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Test
    void pageConvertsWholePageWithBatchLookup() {
        ProductPageQuery query = new ProductPageQuery();
        query.setPage(1);
        query.setPageSize(20);

        PageResult<ProductVO> result = productService.page(query);

        assertNotNull(result, "分页结果不应为 null");
        assertNotNull(result.getList(), "商品列表不应为 null");
        assertFalse(result.getList().isEmpty(), "应返回商品（前置：库中有上架商品）");

        // 库里存在商品关联了已逻辑删除的分类 / 品牌（category 2 条、brand 14 条被逻辑删除），
        // 逻辑删除过滤后这些商品的分类名 / 品牌名为空属当前预期。
        // TODO: 待业务确认是恢复被删分类/品牌还是给商品重新关联后，把断言收紧为「必填」。
        Set<Long> validCategoryIds = categoryMapper.selectList(null).stream()
                .map(CategoryDO::getId)
                .collect(Collectors.toSet());
        Set<Long> validBrandIds = brandMapper.selectList(null).stream()
                .map(BrandDO::getId)
                .collect(Collectors.toSet());

        for (ProductVO vo : result.getList()) {
            assertNotNull(vo.getId(), "商品ID不应为空");
            if (vo.getCategoryId() != null && validCategoryIds.contains(vo.getCategoryId())) {
                assertNotNull(vo.getCategoryName(), "有效分类应回填分类名（预加载未破坏原语义）");
            }
            if (vo.getBrandId() != null && validBrandIds.contains(vo.getBrandId())) {
                assertNotNull(vo.getBrandName(), "有效品牌应回填品牌名（预加载未破坏原语义）");
            }
            assertNotNull(vo.getMinPrice(), "最低价应回填（SKU 批量分组未丢数据）");
            assertNotNull(vo.getMaxPrice(), "最高价应回填");
            assertNotNull(vo.getTotalStock(), "总库存应回填");
            assertNotNull(vo.getSoldCount(), "销量应回填（无销量时为 0，不应为 null）");
        }
    }
}
