package com.shop.controller;

import com.shop.common.Result;
import com.shop.model.entity.BrandDO;
import com.shop.service.BrandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 品牌接口
 * 提供品牌主数据列表查询，供商品表单选择品牌
 *
 * @author shop
 */
@Tag(name = "品牌模块")
@RestController
@RequestMapping("/api/brand")
@RequiredArgsConstructor
public class BrandController {

    private final BrandService brandService;

    /**
     * 获取启用状态的品牌列表（按 sort 升序）
     * <p>
     * 与 {@code /api/category/list} 同级：商品表单（管理后台 / 商家中心）需要下拉选项，
     * 属公开读接口，不含写操作。
     *
     * @return 品牌列表（无数据时返回空列表）
     */
    @GetMapping("/list")
    @Operation(summary = "获取品牌列表（启用状态，按排序升序）")
    public Result<List<BrandDO>> list() {
        return Result.success(brandService.list());
    }
}
