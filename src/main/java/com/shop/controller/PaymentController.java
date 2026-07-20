package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.shop.common.Result;
import com.shop.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 支付回调接口
 * 模拟支付网关回调处理，生产环境下由支付宝/微信异步通知
 * 注意：回调 URL 需在支付网关后台配置，由网关主动调用而非前端直连
 */
@Slf4j
@Tag(name = "支付模块")
@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
public class PaymentController {

    private final OrderService orderService;

    @PostMapping("/callback")
    @Operation(summary = "支付回调（模拟）")
    public String paymentCallback(@RequestParam String orderNo,
                                  @RequestParam Integer payType) {
        log.info("收到支付回调, orderNo: {}, payType: {}", orderNo, payType);
        orderService.handlePaymentCallback(orderNo, payType);
        // 支付网关要求返回 success 才算处理成功
        return "success";
    }

    @SaCheckLogin
    @PostMapping("/mock-pay")
    @Operation(summary = "模拟支付（测试用）")
    public Result<Void> mockPay(@RequestParam String orderNo,
                                @RequestParam(defaultValue = "1") Integer payType) {
        orderService.handlePaymentCallback(orderNo, payType);
        return Result.success("支付成功");
    }
}
