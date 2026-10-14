package com.shop.rec.controller;

import com.shop.common.BusinessException;
import com.shop.common.Result;
import com.shop.common.StpAdminUtil;
import com.shop.rec.constant.RecErrorCode;
import com.shop.rec.model.entity.UserProfileDO;
import com.shop.rec.profile.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 画像管理接口（外部接口 E-08，由 M-03 承载）
 *
 * <p>
 * 两类职责：
 * <ol>
 *     <li><b>版本回滚</b>：落实需求分析 3.3「用户画像至少保留最近 3 个历史版本，支持回滚」。
 *         接口与界面入口缺一不可——仅有流程描述而无调用入口时，该要求无法验收；</li>
 *     <li><b>全量重建</b>：触发一次画像生成任务，用于用户与行为数据整体替换后的立即生效。
 *         原先画像只依赖 {@code @Scheduled}（每日 02:00），换完数据只能干等次日凌晨。</li>
 * </ol>
 *
 * <p>
 * <b>失败语义</b>：目标版本不在保留范围内时拒绝回滚并返回错误码 4004，
 * 当前有效版本保持不变（详细设计 5.1.8）。
 *
 * @author shop
 */
@Tag(name = "推荐-画像版本")
@RestController
@RequestMapping("/api/rec/profiles")
@Profile("rec")
@RequiredArgsConstructor
public class RecProfileController {

    private final UserProfileService userProfileService;

    /**
     * 触发画像全量重建（异步）
     *
     * <p><b>为什么需要这个入口</b>：画像原先只依赖 {@code @Scheduled}（每日 02:00），
     * 换完用户与行为数据只能干等次日凌晨，没有任何办法立即生效。手动重建补齐了这个缺口。
     *
     * <p>异步执行，已有任务在执行中时拒绝重复触发（错误码 4005）；调用方按
     * {@code /rebuild-status} 轮询，以 {@code rebuilding} 变为 false 作为结束信号，
     * 再按 {@code lastRebuildSuccess} 判断成败——只看结束时间会把「跑完但一条没生成」
     * 误判为成功。
     *
     * @return 受理结果
     */
    @PostMapping("/rebuild")
    @Operation(summary = "触发画像全量重建（异步）")
    public Result<Void> rebuild() {
        checkAdminLogin();
        if (!userProfileService.triggerRebuild()) {
            throw new BusinessException(RecErrorCode.REBUILD_CONFLICT, "已有画像任务在执行中，请稍后再试");
        }
        return Result.success("已触发画像重建", null);
    }

    /**
     * 查询最近一次画像任务的状态
     *
     * @return 任务状态（是否进行中、结束时间、成败、结果说明）
     */
    @GetMapping("/rebuild-status")
    @Operation(summary = "查询画像重建状态")
    public Result<UserProfileService.RebuildStatus> rebuildStatus() {
        checkAdminLogin();
        return Result.success(userProfileService.rebuildStatus());
    }

    /**
     * 查询指定用户的画像版本列表
     *
     * <p>回滚的前置接口：运营需先看到保留范围内的版本与偏好内容，才能选定目标版本。
     * 对应需求分析 3.3「用户画像至少保留最近 3 个历史版本，支持回滚」。
     *
     * @param userId 用户标识
     * @return 画像版本列表（按版本号降序）
     */
    @GetMapping("/{userId}/versions")
    @Operation(summary = "查询用户画像版本列表")
    public Result<List<UserProfileDO>> versions(@PathVariable Long userId) {
        checkAdminLogin();
        return Result.success(userProfileService.listVersions(userId));
    }

    /**
     * 回滚指定用户的画像版本
     *
     * @param userId 用户标识
     * @param request 回滚请求（含目标版本号）
     * @return 回滚结果
     */
    @PostMapping("/{userId}/rollback")
    @Operation(summary = "回滚用户画像版本")
    public Result<RollbackResult> rollback(@PathVariable Long userId,
                                           @RequestBody RollbackRequest request) {
        checkAdminLogin();
        try {
            userProfileService.rollbackProfile(userId, request.getTargetVersion());
        } catch (IllegalArgumentException e) {
            // 目标版本不可用：拒绝回滚，保持当前有效版本不变
            throw new BusinessException(RecErrorCode.PROFILE_VERSION_UNAVAILABLE, e.getMessage());
        }
        RollbackResult result = new RollbackResult();
        result.setUserId(userId);
        result.setCurrentVersion(request.getTargetVersion());
        return Result.success("回滚成功", result);
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

    /**
     * 回滚请求
     */
    @Data
    public static class RollbackRequest {

        /** 目标版本号（yyyyMMdd） */
        private Integer targetVersion;

        /** 回滚原因，用于审计 */
        private String remark;
    }

    /**
     * 回滚结果
     */
    @Data
    public static class RollbackResult {

        /** 用户标识 */
        private Long userId;

        /** 回滚后的当前有效版本 */
        private Integer currentVersion;
    }
}
