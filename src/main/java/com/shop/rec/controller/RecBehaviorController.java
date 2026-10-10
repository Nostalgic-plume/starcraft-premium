package com.shop.rec.controller;

import com.shop.common.Result;
import com.shop.rec.collect.BehaviorCollectService;
import com.shop.rec.collect.CollectReject;
import com.shop.rec.model.dto.BehaviorReportDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行为上报接口（外部接口 E-01，由 M-01 承载）
 *
 * <p>
 * 采集用户在商品详情、购物车、收藏、下单页面的浏览 / 加购 / 收藏 / 购买四类行为事件。
 * 接口只做校验与投递，不等待下游落库完成，保证 100ms 内返回（对应详细设计 3.1 步骤 10）。
 *
 * <p>
 * <b>鉴权</b>：校验登录态；无有效登录态返回 1002 且不采集任何数据。
 * 用户标识一律由服务端从登录态解析，不接受请求参数指定（防越权）。
 *
 * <p>
 * <b>进程归属</b>：本接口内嵌于商城后端，标注 {@code @Profile("!rec")}，推荐服务进程不加载。
 *
 * @author shop
 */
@Tag(name = "推荐-行为采集")
@RestController
@RequestMapping("/api/rec/behavior")
@Profile("!rec")
@RequiredArgsConstructor
public class RecBehaviorController {

    /** 来源标识请求头：客户端标识，用于爬虫判定与限频 */
    private static final String HEADER_CLIENT_SOURCE = "X-Client-Source";

    private final BehaviorCollectService behaviorCollectService;

    /**
     * 上报用户行为事件
     *
     * <p>被防护规则丢弃的事件按原因返回对应状态码（重复 1005 / 限频 1006 / 采样 1007）；
     * 爬虫与无效行为按受理成功返回，不向调用方暴露判定结果。
     *
     * @param dto    上报请求，字段校验由全局异常处理器接管
     * @param source 客户端来源标识，可选；缺失时跳过爬虫判定
     * @return 受理结果
     */
    @PostMapping("/report")
    @Operation(summary = "上报用户行为事件")
    public Result<Void> report(@Valid @RequestBody BehaviorReportDTO dto,
                               @RequestHeader(value = HEADER_CLIENT_SOURCE, required = false) String source) {
        CollectReject reject = behaviorCollectService.report(dto, source);
        if (reject == null || reject.isAccepted()) {
            return Result.<Void>success("受理成功", null);
        }
        return Result.error(reject.getCode(), reject.getReason());
    }
}
