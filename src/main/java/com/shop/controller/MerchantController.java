package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.shop.common.Result;
import com.shop.common.PageResult;
import com.shop.constant.PermissionConstants;
import com.shop.model.dto.ProductDTO;
import com.shop.model.dto.ShopLoginDTO;
import com.shop.model.query.ProductPageQuery;
import com.shop.model.vo.DashboardStatsVO;
import com.shop.model.vo.OrderVO;
import com.shop.model.vo.ProductVO;
import com.shop.service.OrderService;
import com.shop.service.ProductService;
import com.shop.service.ShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 商家端接口
 * 商家使用 shop 表独立账号登录，shop_id 即 Sa-Token loginId
 * 所有业务接口通过 ShopService 获取 shop_id 并委托 Service 处理
 */
@Slf4j
@Tag(name = "商家模块")
@RestController
@RequestMapping("/api/merchant")
@RequiredArgsConstructor
public class MerchantController {

    private final ShopService shopService;
    private final OrderService orderService;
    private final ProductService productService;

    // ==================== 商家独立登录 ====================

    @PostMapping("/login")
    @Operation(summary = "商家登录（使用 shop 表独立账号）")
    public Result<Map<String, Object>> login(@Valid @RequestBody ShopLoginDTO dto) {
        return Result.success(shopService.login(dto));
    }

    // ==================== 数据统计 ====================

    @SaCheckLogin
    @GetMapping("/stats/dashboard")
    @Operation(summary = "商家控制台概览（本店数据）")
    public Result<DashboardStatsVO> dashboard() {
        return Result.success(shopService.getDashboard());
    }

    // ==================== 订单管理 ====================

    @SaCheckLogin
    @GetMapping("/order/page")
    @Operation(summary = "商家订单分页列表（仅本店订单）")
    public Result<PageResult<OrderVO>> orderPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Integer status) {
        return Result.success(orderService.merchantPageOrders(page, pageSize, status));
    }

    @SaCheckLogin
    @GetMapping("/order/detail/{orderId}")
    @Operation(summary = "商家订单详情")
    public Result<OrderVO> orderDetail(@PathVariable Long orderId) {
        return Result.success(orderService.merchantGetDetail(orderId));
    }

    @SaCheckLogin
    @PostMapping("/order/deliver")
    @Operation(summary = "商家发货")
    public Result<Void> deliver(@RequestBody Map<String, Object> body) {
        Long orderId = Long.valueOf(body.get("orderId").toString());
        String logisticsNo = (String) body.getOrDefault("logisticsNo", "");
        orderService.merchantDeliver(orderId, logisticsNo);
        return Result.success("发货成功");
    }

    // ==================== 商品管理 ====================

    @SaCheckLogin
    @GetMapping("/product/page")
    @Operation(summary = "商家商品列表（仅本店商品）")
    public Result<PageResult<ProductVO>> productPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword) {
        ProductPageQuery query = new ProductPageQuery();
        query.setPage(page);
        query.setPageSize(pageSize);
        query.setKeyword(keyword);
        return Result.success(productService.merchantPage(query));
    }

    @SaCheckPermission(PermissionConstants.PRODUCT_ADD)
    @PostMapping("/product/add")
    @Operation(summary = "商家新增商品（自动绑定本店）")
    public Result<Long> addProduct(@Valid @RequestBody ProductDTO dto) {
        return Result.success("新增成功", productService.addProduct(dto));
    }

    @SaCheckPermission(PermissionConstants.PRODUCT_STATUS)
    @PutMapping("/product/status/{id}")
    @Operation(summary = "商家更新商品状态")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        productService.updateStatus(id, status);
        return Result.success();
    }
}
