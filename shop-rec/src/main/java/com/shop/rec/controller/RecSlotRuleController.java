package com.shop.rec.controller;

import com.shop.common.BusinessException;
import com.shop.common.Result;
import com.shop.common.StpAdminUtil;
import com.shop.rec.config.SlotRuleManageService;
import com.shop.rec.constant.RecErrorCode;
import com.shop.rec.model.entity.RecFilterRuleDO;
import com.shop.rec.model.entity.RecFixedSlotDO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 固定坑位与过滤规则管理接口（外部接口 E-04，由 M-09 承载）
 *
 * <p>
 * 供运营维护榜单固定坑位与过滤规则。坑位位置冲突时拒绝写入（错误码 4002）；
 * 规则变更后立即重建内存库过滤清单，下一次请求即读到最新规则。
 *
 * <p>
 * <b>入参说明</b>：演示项目直接以实体承载请求体，字段与库表一致；生产环境应改为独立的
 * DTO/VO 以免外部感知表结构。
 *
 * @author shop
 */
@Tag(name = "推荐-坑位与规则")
@RestController
@RequestMapping("/api/rec")
@Profile("rec")
@RequiredArgsConstructor
public class RecSlotRuleController {

    private final SlotRuleManageService slotRuleManageService;

    // ==================== 固定坑位 ====================

    /**
     * 查询全部坑位
     *
     * @return 坑位列表
     */
    @GetMapping("/slots")
    @Operation(summary = "查询固定坑位")
    public Result<List<RecFixedSlotDO>> listSlots() {
        checkAdminLogin();
        return Result.success(slotRuleManageService.listSlots());
    }

    /**
     * 新增坑位
     *
     * @param slot 坑位配置
     * @return 保存后的坑位
     */
    @PostMapping("/slots")
    @Operation(summary = "新增固定坑位")
    public Result<RecFixedSlotDO> createSlot(@RequestBody RecFixedSlotDO slot) {
        String operator = checkAdminLogin();
        return Result.success("保存成功", slotRuleManageService.saveSlot(slot, operator));
    }

    /**
     * 修改坑位
     *
     * @param slot 坑位配置（须含主键）
     * @return 保存后的坑位
     */
    @PutMapping("/slots")
    @Operation(summary = "修改固定坑位")
    public Result<RecFixedSlotDO> updateSlot(@RequestBody RecFixedSlotDO slot) {
        String operator = checkAdminLogin();
        return Result.success("保存成功", slotRuleManageService.saveSlot(slot, operator));
    }

    /**
     * 删除坑位
     *
     * @param id 坑位标识
     * @return 操作结果
     */
    @DeleteMapping("/slots/{id}")
    @Operation(summary = "删除固定坑位")
    public Result<Void> deleteSlot(@PathVariable Long id) {
        checkAdminLogin();
        slotRuleManageService.deleteSlot(id);
        return Result.success();
    }

    // ==================== 过滤规则 ====================

    /**
     * 查询全部过滤规则
     *
     * @return 规则列表
     */
    @GetMapping("/rules")
    @Operation(summary = "查询过滤规则")
    public Result<List<RecFilterRuleDO>> listRules() {
        checkAdminLogin();
        return Result.success(slotRuleManageService.listRules());
    }

    /**
     * 新增过滤规则
     *
     * @param rule 规则
     * @return 保存后的规则
     */
    @PostMapping("/rules")
    @Operation(summary = "新增过滤规则")
    public Result<RecFilterRuleDO> createRule(@RequestBody RecFilterRuleDO rule) {
        String operator = checkAdminLogin();
        return Result.success("保存成功", slotRuleManageService.saveRule(rule, operator));
    }

    /**
     * 修改过滤规则
     *
     * @param rule 规则（须含主键）
     * @return 保存后的规则
     */
    @PutMapping("/rules")
    @Operation(summary = "修改过滤规则")
    public Result<RecFilterRuleDO> updateRule(@RequestBody RecFilterRuleDO rule) {
        String operator = checkAdminLogin();
        return Result.success("保存成功", slotRuleManageService.saveRule(rule, operator));
    }

    /**
     * 删除过滤规则
     *
     * @param id 规则标识
     * @return 操作结果
     */
    @DeleteMapping("/rules/{id}")
    @Operation(summary = "删除过滤规则")
    public Result<Void> deleteRule(@PathVariable Long id) {
        checkAdminLogin();
        slotRuleManageService.deleteRule(id);
        return Result.success();
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
}
