package com.shop.rec.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.BusinessException;
import com.shop.rec.common.RecRedis;
import com.shop.rec.constant.RecErrorCode;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.mapper.RecFilterRuleMapper;
import com.shop.rec.mapper.RecFixedSlotMapper;
import com.shop.rec.model.entity.RecFilterRuleDO;
import com.shop.rec.model.entity.RecFixedSlotDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 固定坑位与过滤规则管理（M-09）
 *
 * <p>
 * 对应《详细设计说明书》3.9 与外部接口 E-04。两类配置都遵循同一约定：
 * <b>校验不通过即拒绝写入并保留旧值</b>（错误码 4001/4002），写入成功后同步内存库副本，
 * 由在线链路热更新读取，无需发版。
 *
 * <p>
 * <b>过滤清单同步</b>：规则变更后立即重建对应的内存库集合（{@code rec:filter:{ruleType}}），
 * 使融合过滤环节在下一次请求即读到最新规则。规则数据缺失的预留类型（地域/会员限定/低质商品）
 * 同样参与同步，只是当前没有数据、集合为空、过滤不生效。
 *
 * @author shop
 */
@Slf4j
@Service
@Profile("rec")
@RequiredArgsConstructor
public class SlotRuleManageService {

    /** 坑位冲突错误码 */
    private static final int CODE_SLOT_CONFLICT = RecErrorCode.SLOT_POSITION_CONFLICT;

    /** 启用状态 */
    private static final int STATUS_ENABLED = 1;

    private final RecFixedSlotMapper recFixedSlotMapper;

    private final RecFilterRuleMapper recFilterRuleMapper;

    private final RecRedis recRedis;

    // ==================== 固定坑位 ====================

    /**
     * 查询全部坑位（按位置升序）
     *
     * @return 坑位列表
     */
    public List<RecFixedSlotDO> listSlots() {
        return recFixedSlotMapper.selectList(new LambdaQueryWrapper<RecFixedSlotDO>()
                .orderByAsc(RecFixedSlotDO::getSlotPosition));
    }

    /**
     * 新增或修改坑位
     *
     * <p>写入前校验时段冲突：同一时段内同一位置不允许存在多条启用记录
     * （唯一索引 uk_position_time 只能约束同一开始时间，重叠时段需业务校验兜底）。
     *
     * @param slot     坑位配置
     * @param operator 操作人
     * @return 保存后的坑位
     * @throws BusinessException 校验不通过或位置冲突时抛出
     */
    @Transactional(rollbackFor = Exception.class, transactionManager = "recTransactionManager")
    public RecFixedSlotDO saveSlot(RecFixedSlotDO slot, String operator) {
        if (slot.getSlotPosition() == null || slot.getSlotPosition() < 1) {
            throw new BusinessException(RecErrorCode.CONFIG_INVALID, "坑位位置必须为正整数");
        }
        if (slot.getItemId() == null) {
            throw new BusinessException(RecErrorCode.CONFIG_INVALID, "坑位商品不能为空");
        }
        if (slot.getStartTime() == null || slot.getEndTime() == null
                || !slot.getEndTime().isAfter(slot.getStartTime())) {
            throw new BusinessException(RecErrorCode.CONFIG_INVALID, "生效结束时间必须晚于开始时间");
        }

        long conflict = recFixedSlotMapper.selectCount(new LambdaQueryWrapper<RecFixedSlotDO>()
                .eq(RecFixedSlotDO::getSlotPosition, slot.getSlotPosition())
                .eq(RecFixedSlotDO::getStatus, STATUS_ENABLED)
                .ne(slot.getId() != null, RecFixedSlotDO::getId, slot.getId())
                .lt(RecFixedSlotDO::getStartTime, slot.getEndTime())
                .gt(RecFixedSlotDO::getEndTime, slot.getStartTime()));
        if (conflict > 0) {
            throw new BusinessException(CODE_SLOT_CONFLICT,
                    "坑位位置冲突：位置 " + slot.getSlotPosition() + " 在该时段已存在启用记录");
        }

        if (slot.getStatus() == null) {
            slot.setStatus(STATUS_ENABLED);
        }
        slot.setOperator(operator);
        slot.setUpdateTime(LocalDateTime.now());
        if (slot.getId() == null) {
            recFixedSlotMapper.insert(slot);
        } else {
            recFixedSlotMapper.updateById(slot);
        }
        log.info("固定坑位已保存, id: {}, 位置: {}, operator: {}", slot.getId(), slot.getSlotPosition(), operator);
        return slot;
    }

    /**
     * 删除坑位
     *
     * @param id 坑位标识
     */
    @Transactional(rollbackFor = Exception.class, transactionManager = "recTransactionManager")
    public void deleteSlot(Long id) {
        recFixedSlotMapper.deleteById(id);
        log.info("固定坑位已删除, id: {}", id);
    }

    // ==================== 过滤规则 ====================

    /**
     * 查询全部过滤规则（按类型与标识排序）
     *
     * @return 规则列表
     */
    public List<RecFilterRuleDO> listRules() {
        return recFilterRuleMapper.selectList(new LambdaQueryWrapper<RecFilterRuleDO>()
                .orderByAsc(RecFilterRuleDO::getRuleType)
                .orderByAsc(RecFilterRuleDO::getTargetValue));
    }

    /**
     * 新增或修改过滤规则
     *
     * @param rule     规则
     * @param operator 操作人
     * @return 保存后的规则
     * @throws BusinessException 校验不通过时抛出
     */
    @Transactional(rollbackFor = Exception.class, transactionManager = "recTransactionManager")
    public RecFilterRuleDO saveRule(RecFilterRuleDO rule, String operator) {
        if (rule.getRuleType() == null || rule.getRuleType().isBlank()) {
            throw new BusinessException(RecErrorCode.CONFIG_INVALID, "规则类型不能为空");
        }
        if (rule.getTargetType() == null || rule.getTargetValue() == null || rule.getTargetValue().isBlank()) {
            throw new BusinessException(RecErrorCode.CONFIG_INVALID, "作用对象与取值不能为空");
        }
        if (rule.getStatus() == null) {
            rule.setStatus(STATUS_ENABLED);
        }
        rule.setOperator(operator);
        rule.setUpdateTime(LocalDateTime.now());
        if (rule.getId() == null) {
            recFilterRuleMapper.insert(rule);
        } else {
            recFilterRuleMapper.updateById(rule);
        }
        syncFilterList(rule.getRuleType());
        log.info("过滤规则已保存, id: {}, 类型: {}, operator: {}", rule.getId(), rule.getRuleType(), operator);
        return rule;
    }

    /**
     * 删除过滤规则
     *
     * @param id 规则标识
     */
    @Transactional(rollbackFor = Exception.class, transactionManager = "recTransactionManager")
    public void deleteRule(Long id) {
        RecFilterRuleDO rule = recFilterRuleMapper.selectById(id);
        recFilterRuleMapper.deleteById(id);
        if (rule != null) {
            syncFilterList(rule.getRuleType());
        }
        log.info("过滤规则已删除, id: {}", id);
    }

    /**
     * 重建指定类型的过滤清单
     *
     * <p>先清空再写入，避免已停用的规则残留在清单中继续生效。
     *
     * @param ruleType 规则类型
     */
    private void syncFilterList(String ruleType) {
        try {
            String key = RecRedisKeys.filter(ruleType);
            recRedis.delete(key);
            List<RecFilterRuleDO> rules = recFilterRuleMapper.selectList(new LambdaQueryWrapper<RecFilterRuleDO>()
                    .eq(RecFilterRuleDO::getRuleType, ruleType)
                    .eq(RecFilterRuleDO::getStatus, STATUS_ENABLED));
            if (rules.isEmpty()) {
                return;
            }
            Set<String> values = new HashSet<>(rules.size());
            for (RecFilterRuleDO rule : rules) {
                values.add(rule.getTargetValue());
            }
            recRedis.saddAll(key, values);
        } catch (Exception e) {
            log.error("过滤清单同步失败, ruleType: {}（下次规则变更时会重试）", ruleType, e);
        }
    }
}
