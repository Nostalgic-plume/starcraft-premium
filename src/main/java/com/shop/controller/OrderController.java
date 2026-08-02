package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.shop.common.PageResult;
import com.shop.common.Result;
import com.shop.model.dto.OrderCreateDTO;
import com.shop.model.vo.OrderVO;
import com.shop.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 订单接口
 * 订单全生命周期：防重令牌获取 → 创建订单 → 取消订单 → 确认收货 → 订单查询
 */
@Tag(name = "订单模块")
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @SaCheckLogin
    @GetMapping("/token")
    @Operation(summary = "获取下单防重令牌")
    public Result<Map<String, String>> getToken() {
        String token = orderService.getOrderToken();
        return Result.success(Map.of("token", token));
    }

    @SaCheckLogin
    @PostMapping("/create")
    @Operation(summary = "创建订单（异步削峰，返回 processingId 供前端轮询）")
    public Result<String> createOrder(@Valid @RequestBody OrderCreateDTO dto) {
        return Result.success("下单请求已提交", orderService.createOrder(dto));
    }

    @SaCheckLogin
    @GetMapping("/status/{processingId}")
    @Operation(summary = "查询异步落单状态（轮询接口）")
    public Result<Map<String, Object>> processingStatus(@PathVariable String processingId) {
        return Result.success(orderService.getProcessingStatus(processingId));
    }

    @SaCheckLogin
    @PostMapping("/cancel/{orderId}")
    @Operation(summary = "取消订单")
    public Result<Void> cancelOrder(@PathVariable Long orderId) {
        orderService.cancelOrder(orderId);
        return Result.success();
    }

    @SaCheckLogin
    @PostMapping("/receive/{orderId}")
    @Operation(summary = "确认收货")
    public Result<Void> confirmReceive(@PathVariable Long orderId) {
        orderService.confirmReceive(orderId);
        return Result.success();
    }

    @SaCheckLogin
    @GetMapping("/detail/{orderId}")
    @Operation(summary = "订单详情")
    public Result<OrderVO> detail(@PathVariable Long orderId) {
        return Result.success(orderService.getDetail(orderId));
    }

    @SaCheckLogin
    @GetMapping("/my")
    @Operation(summary = "我的订单列表")
    public Result<PageResult<OrderVO>> myOrders(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Integer status) {
        return Result.success(orderService.myOrders(page, pageSize, status));
    }
}
