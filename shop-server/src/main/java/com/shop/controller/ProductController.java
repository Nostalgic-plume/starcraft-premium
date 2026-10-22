package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.shop.common.PageResult;
import com.shop.common.Result;
import com.shop.constant.PermissionConstants;
import com.shop.model.dto.ProductDTO;
import com.shop.model.query.ProductPageQuery;
import com.shop.model.vo.ProductVO;
import com.shop.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 商品接口
 * 浏览类接口无需登录；新增/编辑/上下架需要对应权限
 *
 * @author 陈
 */
@Tag(name = "商品模块")
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * 商品分页列表（公开接口）
     * 支持分类/关键词/价格等多条件筛选，由 @Valid 校验分页参数
     *
     * @param query 分页查询参数（含 page/pageSize/categoryId/keyword 等），page 从 1 开始
     * @return 商品分页结果
     */
    @GetMapping("/page")
    @Operation(summary = "商品分页列表")
    public Result<PageResult<ProductVO>> page(@Valid ProductPageQuery query) {
        return Result.success(productService.page(query));
    }

    /**
     * 商品详情（公开接口）
     * 走逻辑过期缓存 + 布隆过滤器防穿透/击穿，返回商品及规格信息
     *
     * @param id 商品ID（不存在的 ID 由布隆过滤器拦截）
     * @return 商品详情
     */
    @GetMapping("/detail/{id}")
    @Operation(summary = "商品详情")
    public Result<ProductVO> detail(@PathVariable Long id) {
        return Result.success(productService.getDetail(id));
    }

    /**
     * 新增商品（需 product:add 权限，商家/管理员可用）
     *
     * @param dto 商品信息请求体（含名称、价格、SKU 等），由 @Valid 校验
     * @return 新增成功的商品ID
     */
    @SaCheckPermission(value = {PermissionConstants.PRODUCT_ADD}, type ="admin")
    @PostMapping("/add")
    @Operation(summary = "新增商品（商家/管理员）")
    public Result<Long> add(@Valid @RequestBody ProductDTO dto) {
        return Result.success("新增成功", productService.addProduct(dto));
    }

    /**
     * 编辑商品信息（需 product:edit 权限，管理员用）
     * 管理员走此入口时不校验 shop_id 归属（可编辑全平台商品）；
     * 商家请使用 {@code PUT /api/merchant/product/{id}}，该入口强制本店隔离
     *
     * @param id  商品ID
     * @param dto 商品信息请求体，由 @Valid 校验
     * @return 操作成功提示
     */
    @SaCheckPermission(value = {PermissionConstants.PRODUCT_EDIT}, type ="admin")
    @PutMapping("/{id}")
    @Operation(summary = "编辑商品信息（管理员）")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ProductDTO dto) {
        productService.updateProduct(id, dto);
        return Result.success();
    }

    /**
     * 更新商品状态（需 product:status 权限，商家/管理员可用）
     *
     * @param id     商品ID
     * @param status 目标状态：0=待审核 1=上架 2=下架
     * @return 操作成功提示
     */
    @SaCheckPermission(value = {PermissionConstants.PRODUCT_STATUS}, type ="admin")
    @PutMapping("/status/{id}")
    @Operation(summary = "更新商品状态（上架/下架，商家/管理员）")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        productService.updateStatus(id, status);
        return Result.success();
    }

    /**
     * 删除商品（需 product:delete 权限，仅管理员）
     * <p>
     * 逻辑删除并同步清理详情缓存与向量/ES 索引；商家侧不开放删除（权限表未授予 merchant）。
     *
     * @param id 商品ID
     * @return 操作成功提示
     */
    @SaCheckPermission(value = {PermissionConstants.PRODUCT_DELETE}, type = "admin")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除商品（管理员）")
    public Result<Void> delete(@PathVariable Long id) {
        productService.deleteProduct(id);
        return Result.success("删除成功", null);
    }
}
