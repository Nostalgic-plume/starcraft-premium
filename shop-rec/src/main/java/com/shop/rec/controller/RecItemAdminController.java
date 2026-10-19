package com.shop.rec.controller;

import com.shop.common.BusinessException;
import com.shop.common.Result;
import com.shop.common.StpAdminUtil;
import com.shop.rec.constant.RecErrorCode;
import com.shop.rec.item.ItemDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品索引重建接口（由 M-04 承载）
 *
 * <p>
 * <b>为什么需要这个入口</b>：商品维度与候选索引此前完全依赖 {@code @Scheduled}——
 * 全量重建在每日 01:00、类目索引与兜底名单每 10 分钟刷新。这意味着批量调整商品数据后
 * <b>没有任何办法立即生效</b>，只能干等定时任务；而推荐结果会在这段时间里持续引用旧商品。
 *
 * <p>
 * <b>重建做的事</b>：清空商品状态缓存中已失效的残留 → 全量重写可售商品状态 →
 * 重建类目候选索引 → 重建兜底名单。清理这一步是关键：分页遍历只会返回「当前仍存在」的商品，
 * 被物理删除的商品的状态键不会被写入覆盖，也不会被对账任务清掉，
 * 会一直残留并让推荐结果里出现「已不存在但仍在售」的悬空商品。
 *
 * <p>
 * <b>异步与轮询</b>：重建耗时可达数秒至数十秒，接口只负责触发并立即返回；
 * 前端按 {@code /rebuild-status} 轮询，以 {@code rebuilding} 变为 false 作为结束信号，
 * 再按 {@code lastRebuildSuccess} 判断成败。
 *
 * <p>
 * <b>⚠️ 重建期间榜单会短暂降级</b>：清理与重写之间存在空窗，期间查不到商品状态会被过滤，
 * 榜单可能回落到兜底名单。这是低频运维操作，属可接受的预期行为。
 *
 * @author shop
 */
@Tag(name = "推荐-商品索引")
@RestController
@RequestMapping("/api/rec/items")
@Profile("rec")
@RequiredArgsConstructor
public class RecItemAdminController {

    private final ItemDataService itemDataService;

    /**
     * 查询最近一次重建状态
     *
     * @return 重建状态（是否进行中、结束时间、成败、结果说明）
     */
    @GetMapping("/rebuild-status")
    @Operation(summary = "查询商品索引重建状态")
    public Result<ItemDataService.RebuildStatus> status() {
        checkAdminLogin();
        return Result.success(itemDataService.rebuildStatus());
    }

    /**
     * 触发商品索引全量重建
     *
     * <p>异步执行；已有重建在执行中时拒绝重复触发（错误码 4005）。
     *
     * @return 受理结果
     */
    @PostMapping("/rebuild")
    @Operation(summary = "触发商品索引全量重建（异步）")
    public Result<Void> rebuild() {
        checkAdminLogin();
        if (!itemDataService.triggerRebuild()) {
            throw new BusinessException(RecErrorCode.REBUILD_CONFLICT, "已有重建任务在执行中，请稍后再试");
        }
        return Result.success("已触发全量重建", null);
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
