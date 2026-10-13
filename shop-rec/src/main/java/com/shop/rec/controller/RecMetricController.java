package com.shop.rec.controller;

import com.shop.common.BusinessException;
import com.shop.common.Result;
import com.shop.common.StpAdminUtil;
import com.shop.rec.constant.RecErrorCode;
import com.shop.rec.metric.MetricService;
import com.shop.rec.model.entity.PipelineMetricDO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 链路指标查询接口（由 M-10 承载）
 *
 * <p>
 * 供管理后台的运行看板读取各环节指标。M-10 此前只写不读——指标落库后没有任何查询入口，
 * 「运行看板」页面因此无数据可取；本接口补上读路径。
 *
 * <p>
 * <b>两个接口的分工</b>：{@code /catalog} 返回指标定义（中文名、单位、预警阈值、好坏方向），
 * 看板据此自动渲染卡片并按阈值标色，新增指标时前端无需改动；{@code /metrics} 返回明细。
 * 阈值定义在后端，前端只做展示，避免两端各写一份而漂移。
 *
 * @author shop
 */
@Tag(name = "推荐-运行指标")
@RestController
@RequestMapping("/api/rec/metrics")
@Profile("rec")
@RequiredArgsConstructor
public class RecMetricController {

    /** 默认查询最近小时数 */
    private static final int DEFAULT_HOURS = 24;

    /** 查询跨度上限（7 天）：再往前看应走归档表，而非明细表 */
    private static final int MAX_HOURS = 24 * 7;

    /** 默认返回条数 */
    private static final int DEFAULT_LIMIT = 100;

    private final MetricService metricService;

    /**
     * 查询指标清单
     *
     * @return 指标定义列表（含中文名、单位、预警阈值与好坏方向）
     */
    @GetMapping("/catalog")
    @Operation(summary = "查询指标清单（看板渲染用）")
    public Result<List<MetricService.MetricDefinition>> catalog() {
        checkAdminLogin();
        return Result.success(MetricService.catalog());
    }

    /**
     * 查询链路指标明细
     *
     * @param metricName 指标名，可选；为空返回全部指标
     * @param hours      查询最近多少小时，可选；缺省 24，上限 168
     * @param limit      返回条数上限，可选；缺省 100，由 Mapper 钳制到 500
     * @return 指标明细列表（按统计时间降序）
     */
    @GetMapping
    @Operation(summary = "查询链路指标明细")
    public Result<List<PipelineMetricDO>> list(@RequestParam(required = false) String metricName,
                                               @RequestParam(required = false) Integer hours,
                                               @RequestParam(required = false) Integer limit) {
        checkAdminLogin();
        // 越界的 hours 一律回落到默认值，而不是截断到上限：
        // 截断会让「查 30 天」静默变成「查 7 天」，调用方看到的数据范围与预期不符却无从察觉
        int span = (hours == null || hours < 1 || hours > MAX_HOURS) ? DEFAULT_HOURS : hours;
        int size = (limit == null || limit < 1) ? DEFAULT_LIMIT : limit;
        LocalDateTime to = LocalDateTime.now();
        return Result.success(metricService.listMetrics(to.minusHours(span), to, metricName, size));
    }

    /**
     * 校验管理后台登录态
     *
     * @throws BusinessException 未登录时抛出（错误码 4003）
     */
    private void checkAdminLogin() {
        if (!StpAdminUtil.INSTANCE.isLogin()) {
            throw new BusinessException(RecErrorCode.PERMISSION_DENIED, "权限不足，请使用管理后台账号登录");
        }
    }
}
