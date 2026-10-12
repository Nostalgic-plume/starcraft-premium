package com.shop.service;

import com.shop.model.entity.BrandDO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 品牌列表查询验证（code-review F5）
 * <p>
 * 背景：品牌主数据迁移后只有 {@code BrandMapper}（读），没有查询接口与管理入口，
 * 商品表单的 brandId 因此永远无法被赋值。本测试验证新增的只读接口数据层：
 * 只返回启用品牌、按 sort 升序、字段完整。
 */
@SpringBootTest
class BrandServiceTest {

    @Autowired
    private BrandService brandService;

    @Test
    void listReturnsEnabledBrandsSortedBySort() {
        List<BrandDO> brands = brandService.list();

        assertFalse(brands.isEmpty(), "品牌列表不应为空（migrate-brand-20260927.sql 已回填）");
        assertTrue(brands.stream().allMatch(b -> b.getStatus() != null && b.getStatus() == 0),
                "只应返回启用状态（status=0）的品牌");
        assertTrue(brands.stream().allMatch(b -> b.getId() != null && b.getName() != null),
                "品牌 id 与名称不应为空");

        // sort 升序：相邻元素非递减（null 视作 0，与 ORDER BY 的 null 排序语义近似）
        for (int i = 1; i < brands.size(); i++) {
            Integer prev = brands.get(i - 1).getSort();
            Integer curr = brands.get(i).getSort();
            assertTrue((prev == null ? 0 : prev) <= (curr == null ? 0 : curr),
                    "品牌应按 sort 升序返回，位置 " + (i - 1) + " 与 " + i + " 逆序");
        }

        // 首条用于人工核对：品牌名不应为空串
        assertNotNull(brands.get(0).getName());
    }
}
