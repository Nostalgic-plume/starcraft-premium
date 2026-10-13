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
 * 画像版本回滚接口（外部接口 E-08，由 M-03 承载）
 *
 * <p>
 * 落实需求分析 3.3「用户画像至少保留最近 3 个历史版本，支持回滚」。
 * 接口与界面入口缺一不可——仅有流程描述而无调用入口时，该要求无法验收。
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
