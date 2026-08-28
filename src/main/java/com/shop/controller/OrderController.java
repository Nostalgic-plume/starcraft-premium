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
 *
 * @author shop
 */
@Tag(name = "订单模块")
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 获取下单防重令牌
     * 创建订单前必须先获取该令牌，防止重复提交
     *
     * @return 防重令牌，data 为 {token: xxx}，创建订单时随请求体提交
     */
    @SaCheckLogin
    @GetMapping("/token")
    @Operation(summary = "获取下单防重令牌")
    public Result<Map<String, String>> getToken() {
        String token = orderService.getOrderToken();
        return Result.success(Map.of("token", token));
    }

    /**
     * 创建订单（异步削峰）
     * 仅投递 RocketMQ 消息即返回，实际落单异步执行；前端需用返回的 processingId 轮询落单状态
     *
     * @param dto 下单请求体（含 token、商品项、地址等），由 @Valid 校验
     * @return 下单请求已提交的提示及 processingId（供轮询状态接口使用）
     */
    @SaCheckLogin
    @PostMapping("/create")
    @Operation(summary = "创建订单（异步削峰，返回 processingId 供前端轮询）")
    public Result<String> createOrder(@Valid @RequestBody OrderCreateDTO dto) {
        return Result.success("下单请求已提交", orderService.createOrder(dto));
    }

    /**
     * 查询异步落单状态（轮询接口）
     * 创建订单后前端定时调用，成功时返回订单ID，失败（库存不足等）返回 FAILED
     *
     * @param processingId 创建订单接口返回的落单处理ID
     * @return 落单状态及结果（成功后含订单ID）
     */
    @SaCheckLogin
    @GetMapping("/status/{processingId}")
    @Operation(summary = "查询异步落单状态（轮询接口）")
    public Result<Map<String, Object>> processingStatus(@PathVariable String processingId) {
        return Result.success(orderService.getProcessingStatus(processingId));
    }

    /**
     * 取消当前登录用户的订单
     * 校验订单归属；仅待支付订单可取消（Service 层状态机校验），取消后回滚库存
     *
     * @param orderId 订单ID
     * @return 操作成功提示
     */
    @SaCheckLogin
    @PostMapping("/cancel/{orderId}")
    @Operation(summary = "取消订单")
    public Result<Void> cancelOrder(@PathVariable Long orderId) {
        orderService.cancelOrder(orderId);
        return Result.success();
    }

    /**
     * 确认收货
     * 校验订单归属；仅已发货订单可确认收货（Service 层状态机校验）
     *
     * @param orderId 订单ID
     * @return 操作成功提示
     */
    @SaCheckLogin
    @PostMapping("/receive/{orderId}")
    @Operation(summary = "确认收货")
    public Result<Void> confirmReceive(@PathVariable Long orderId) {
        orderService.confirmReceive(orderId);
        return Result.success();
    }

    /**
     * 查询当前登录用户的订单详情，校验订单归属
     *
     * @param orderId 订单ID
     * @return 订单详情（含商品快照信息）
     */
    @SaCheckLogin
    @GetMapping("/detail/{orderId}")
    @Operation(summary = "订单详情")
    public Result<OrderVO> detail(@PathVariable Long orderId) {
        return Result.success(orderService.getDetail(orderId));
    }

    /**
     * 查询当前登录用户的订单列表，支持分页与状态筛选
     *
     * @param page     页码，从 1 开始，默认 1
     * @param pageSize 每页条数，默认 10
     * @param status   订单状态：0=待支付 1=已支付 2=已发货 3=已收货 4=已完成 5=已取消 6=已退款，可空表示全部
     * @return 当前用户订单分页结果
     */
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
