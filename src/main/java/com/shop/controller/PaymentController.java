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
 *
 * @author 陈
 */
@Slf4j
@Tag(name = "支付模块")
@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
public class PaymentController {

    private final OrderService orderService;

    /**
     * 支付网关回调（模拟）
     * 由支付网关主动调用；Service 层按订单状态机校验，仅待支付订单可变更已支付（防重复回调）
     *
     * @param orderNo 商户订单号（支付时生成）
     * @param payType 支付方式：1=微信 2=支付宝
     * @return 固定返回 "success"，供支付网关判断处理成功
     */
    @PostMapping("/callback")
    @Operation(summary = "支付回调（模拟）")
    public String paymentCallback(@RequestParam String orderNo,
                                  @RequestParam Integer payType) {
        log.info("收到支付回调, orderNo: {}, payType: {}", orderNo, payType);
        orderService.handlePaymentCallback(orderNo, payType);
        // 支付网关要求返回 success 才算处理成功
        return "success";
    }

    /**
     * 模拟支付（测试用，需登录）
     * 前端测试直接触发支付成功，内部处理逻辑与支付回调一致
     *
     * @param orderNo 待支付的商户订单号
     * @param payType 支付方式：1=微信 2=支付宝，默认 1
     * @return 支付成功提示
     */
    @SaCheckLogin
    @PostMapping("/mock-pay")
    @Operation(summary = "模拟支付（测试用）")
    public Result<Void> mockPay(@RequestParam String orderNo,
                                @RequestParam(defaultValue = "1") Integer payType) {
        orderService.handlePaymentCallback(orderNo, payType);
        return Result.success("支付成功");
    }
}
