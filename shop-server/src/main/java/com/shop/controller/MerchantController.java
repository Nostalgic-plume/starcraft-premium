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
 *
 * @author shop
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

    /**
     * 商家登录
     * 使用 shop 表独立账号登录，成功后返回 Sa-Token token
     *
     * @param dto 登录请求体（含用户名、密码），由 @Valid 做非空与格式校验
     * @return 登录结果，data 为 token 及账号信息的 Map
     */
    @PostMapping("/login")
    @Operation(summary = "商家登录（使用 shop 表独立账号）")
    public Result<Map<String, Object>> login(@Valid @RequestBody ShopLoginDTO dto) {
        return Result.success(shopService.login(dto));
    }

    // ==================== 商家信息 ====================

    /**
     * 获取当前登录商家信息
     *
     * @return 店铺名称/Logo 等商家信息 Map
     */
    @SaCheckLogin(type ="shop")
    @GetMapping("/info")
    @Operation(summary = "获取当前商家信息（店铺名称/Logo，供商家端头部展示）")
    public Result<Map<String, Object>> info() {
        return Result.success(shopService.getCurrentShopInfo());
    }

    // ==================== 数据统计 ====================

    /**
     * 获取当前商家的控制台概览统计（仅本店数据）
     *
     * @return 本店仪表盘汇总数据（订单数、销售额等）
     */
    @SaCheckLogin(type ="shop")
    @GetMapping("/stats/dashboard")
    @Operation(summary = "商家控制台概览（本店数据）")
    public Result<DashboardStatsVO> dashboard() {
        return Result.success(shopService.getDashboard());
    }

    // ==================== 订单管理 ====================

    /**
     * 商家订单分页列表，仅返回本店（shop_id 隔离）订单
     *
     * @param page     页码，从 1 开始，默认 1
     * @param pageSize 每页条数，默认 20
     * @param status   订单状态：0=待支付 1=已支付 2=已发货 3=已收货 4=已完成 5=已取消 6=已退款，可空表示全部
     * @return 本店订单分页结果
     */
    @SaCheckLogin(type ="shop")
    @GetMapping("/order/page")
    @Operation(summary = "商家订单分页列表（仅本店订单）")
    public Result<PageResult<OrderVO>> orderPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Integer status) {
        return Result.success(orderService.merchantPageOrders(page, pageSize, status));
    }

    /**
     * 查询本店订单详情
     * Service 层校验订单归属，非本店订单拒绝返回
     *
     * @param orderId 订单ID
     * @return 订单详情（仅本店订单可查）
     */
    @SaCheckLogin(type ="shop")
    @GetMapping("/order/detail/{orderId}")
    @Operation(summary = "商家订单详情")
    public Result<OrderVO> orderDetail(@PathVariable Long orderId) {
        return Result.success(orderService.merchantGetDetail(orderId));
    }

    /**
     * 商家发货
     * 校验订单归属且订单为已支付状态方可发货
     *
     * @param body 请求体：orderId 为本店订单ID（必填），logisticsNo 为物流单号（可空，默认空串）
     * @return 发货成功提示
     */
    @SaCheckLogin(type ="shop")
    @PostMapping("/order/deliver")
    @Operation(summary = "商家发货")
    public Result<Void> deliver(@RequestBody Map<String, Object> body) {
        Long orderId = Long.valueOf(body.get("orderId").toString());
        String logisticsNo = (String) body.getOrDefault("logisticsNo", "");
        orderService.merchantDeliver(orderId, logisticsNo);
        return Result.success("发货成功");
    }

    // ==================== 商品管理 ====================

    /**
     * 商家商品分页列表，仅返回本店商品（shop_id 隔离）
     *
     * @param page     页码，从 1 开始，默认 1
     * @param pageSize 每页条数，默认 20
     * @param keyword  商品名称关键词搜索，可空
     * @param status   商品状态：0=待审核 1=在售 2=下架，可空表示全部
     * @return 本店商品分页结果
     */
    @SaCheckLogin(type ="shop")
    @GetMapping("/product/page")
    @Operation(summary = "商家商品列表（仅本店商品）")
    public Result<PageResult<ProductVO>> productPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        ProductPageQuery query = new ProductPageQuery();
        query.setPage(page);
        query.setPageSize(pageSize);
        query.setKeyword(keyword);
        query.setStatus(status);
        return Result.success(productService.merchantPage(query));
    }

    /**
     * 商家新增商品，自动绑定本店 shop_id（需 product:add 权限）
     *
     * @param dto 商品信息请求体（含名称、价格、分类等），由 @Valid 校验
     * @return 新增成功的商品ID
     */
    @SaCheckPermission(value = {PermissionConstants.PRODUCT_ADD}, type ="shop")
    @PostMapping("/product/add")
    @Operation(summary = "商家新增商品（自动绑定本店）")
    public Result<Long> addProduct(@Valid @RequestBody ProductDTO dto) {
        return Result.success("新增成功", productService.addProduct(dto));
    }

    /**
     * 商家编辑本店商品信息（需 product:edit 权限）
     * 可修改商品名称、描述、轮播图、详情、分类、品牌、主图及 SKU 列表；
     * SKU 列表中带 id 表示更新、不带 id 表示新增、库中存在但未提交表示逻辑删除。
     * 归属校验在 Service 层按 shop_id 强制（非本店商品返回「无权操作该商品」）
     *
     * @param id  商品ID
     * @param dto 商品信息请求体，由 @Valid 校验
     * @return 操作成功提示
     */
    @SaCheckPermission(value = {PermissionConstants.PRODUCT_EDIT}, type ="shop")
    @PutMapping("/product/{id}")
    @Operation(summary = "商家编辑商品信息（含 SKU 增删改）")
    public Result<Void> updateProduct(@PathVariable Long id, @Valid @RequestBody ProductDTO dto) {
        productService.updateProduct(id, dto);
        return Result.success();
    }

    /**
     * 更新本店商品状态（需 product:status 权限）
     *
     * @param id     商品ID
     * @param status 目标状态：0=待审核 1=上架 2=下架
     * @return 操作成功提示
     */
    @SaCheckPermission(value = {PermissionConstants.PRODUCT_STATUS}, type ="shop")
    @PutMapping("/product/status/{id}")
    @Operation(summary = "商家更新商品状态")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        productService.updateStatus(id, status);
        return Result.success();
    }

    /**
     * 商家调整本店商品 SKU 库存（需 product:edit 权限）
     * 更新 MySQL 后按 Cache Aside 删除 Redis 库存缓存与商品详情缓存，
     * 下次预扣/查询自动加载新值，保证缓存与 DB 一致
     *
     * @param skuId SKU ID
     * @param stock 新库存（>=0）
     * @return 操作成功提示
     */
    @SaCheckPermission(value = {PermissionConstants.PRODUCT_EDIT}, type ="shop")
    @PutMapping("/product/stock/{skuId}")
    @Operation(summary = "商家调整 SKU 库存（Cache Aside 同步 Redis）")
    public Result<Void> updateSkuStock(@PathVariable Long skuId, @RequestParam Integer stock) {
        productService.updateSkuStock(skuId, stock);
        return Result.success();
    }
}
