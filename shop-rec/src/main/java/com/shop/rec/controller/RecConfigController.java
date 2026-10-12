package com.shop.rec.controller;

import com.shop.common.BusinessException;
import com.shop.common.Result;
import com.shop.common.StpAdminUtil;
import com.shop.rec.config.ConfigManageService;
import com.shop.rec.constant.RecErrorCode;
import com.shop.rec.model.dto.ConfigItemDTO;
import com.shop.rec.model.entity.RecConfigDO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 推荐策略配置接口（外部接口 E-03，由 M-09 承载）
 *
 * <p>
 * 供运营在管理后台查询与修改推荐策略参数。校验不通过时拒绝写入并保留旧值（错误码 4001）。
 * 配置改动后各模块默认 30 秒内生效，无需发版与重启（详细设计 3.9）。
 *
 * <p>
 * <b>鉴权</b>：管理后台登录态（loginType=admin）。生产环境应进一步限定为具备运营管理角色的账号，
 * 尤其是强制降级开关 {@code degrade.force}——该配置可将全站推荐置为指定降级状态，
 * 服务层已叠加「强制过期 + 持续告警」，接口层还需配合权限分离。
 *
 * @author shop
 */
@Tag(name = "推荐-策略配置")
@RestController
@RequestMapping("/api/rec/config")
@Profile("rec")
@RequiredArgsConstructor
@Validated
public class RecConfigController {

    private final ConfigManageService configManageService;

    /**
     * 查询配置项
     *
     * @param groupName 配置分组，可选；为空返回全部
     * @return 配置项列表
     */
    @GetMapping
    @Operation(summary = "查询推荐策略配置")
    public Result<List<RecConfigDO>> list(@RequestParam(required = false) String groupName) {
        checkAdminLogin();
        return Result.success(configManageService.listConfigs(groupName));
    }

    /**
     * 批量修改配置
     *
     * @param request 配置项列表
     * @return 生效后的配置版本号
     */
    @PutMapping
    @Operation(summary = "批量修改推荐策略配置")
    public Result<Long> update(@Valid @RequestBody ConfigUpdateRequest request) {
        String operator = checkAdminLogin();
        long version = configManageService.updateConfigs(request.getItems(), operator);
        return Result.success("配置已生效", version);
    }

    /**
     * 校验管理后台登录态
     *
     * @return 操作人账号
     * @throws BusinessException 未登录时抛出（错误码 4003）
     */
    private String checkAdminLogin() {
        if (!StpAdminUtil.INSTANCE.isLogin()) {
            throw new BusinessException(RecErrorCode.PERMISSION_DENIED, "权限不足，请使用管理后台账号登录");
        }
        return String.valueOf(StpAdminUtil.INSTANCE.getLoginId());
    }

    /**
     * 配置修改请求
     */
    @Data
    public static class ConfigUpdateRequest {

        @NotEmpty(message = "配置项不能为空")
        private List<ConfigItemDTO> items;
    }
}
