package com.shop.recproxy;

import com.shop.common.Result;
import com.shop.common.StpUserUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 首页榜单接口（商城后端对外入口）
 *
 * <p>
 * 用户端首页榜单区块的唯一数据来源。商城后端在此完成三件事（对应详细设计 5.4）：
 * <ol>
 *     <li><b>解析身份</b>：从登录态取用户标识，游客为 null；标识不下发前端；</li>
 *     <li><b>转发推荐服务</b>：携带内部凭证与链路标识调用 M-08，透传身份；</li>
 *     <li><b>上游兜底</b>：推荐服务不可达或返回异常时，返回商城原生热销榜（degraded = 上游兜底），
 *         保证首页不出现空白或请求挂起。</li>
 * </ol>
 *
 * <p>
 * <b>首屏与翻页</b>：首屏与下拉刷新不传 {@code snapshotId}（每次重新计算以保证实时性），
 * 仅第 2 页起翻页时回传首屏返回的 {@code snapshotId}。
 *
 * @author shop
 */
@Tag(name = "首页榜单")
@RestController
@RequestMapping("/api/home")
@RequiredArgsConstructor
public class RecRankProxyController {

    /** 链路标识请求头：前端未携带时由商城后端生成 */
    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    private final RecRankProxyService recRankProxyService;

    /**
     * 查询首页榜单
     *
     * @param snapshotId 快照标识，仅翻页时回传
     * @param pageNum    页码
     * @param pageSize   每页条数
     * @param traceId    链路标识，可由前端透传；缺失时生成
     * @return 三段式榜单结果（结构在降级时保持不变）
     */
    @GetMapping("/rank")
    @Operation(summary = "查询首页榜单（转发推荐服务，不可达时返回原生热销榜）")
    public Result<Map<String, Object>> rank(@RequestParam(required = false) String snapshotId,
                                            @RequestParam(required = false) Integer pageNum,
                                            @RequestParam(required = false) Integer pageSize,
                                            @RequestHeader(value = HEADER_TRACE_ID, required = false) String traceId) {
        String effectiveTraceId = traceId == null || traceId.isEmpty()
                ? UUID.randomUUID().toString().replace("-", "")
                : traceId;
        Long userId = resolveUserId();
        return Result.success(recRankProxyService.queryRank(
                snapshotId, pageNum, pageSize, userId, effectiveTraceId));
    }

    /**
     * 解析当前登录用户标识
     *
     * <p>身份一律由服务端从登录态解析，不接受前端指定；未登录返回 null，由推荐服务按游客处理。
     *
     * @return 用户标识；游客返回 null
     */
    private Long resolveUserId() {
        if (!StpUserUtil.INSTANCE.isLogin()) {
            return null;
        }
        return StpUserUtil.INSTANCE.getLoginIdAsLong();
    }
}
