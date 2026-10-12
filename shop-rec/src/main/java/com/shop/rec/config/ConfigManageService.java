package com.shop.rec.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.rec.common.RecRedis;
import com.shop.rec.constant.RecConfigKeys;
import com.shop.rec.constant.RecErrorCode;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.enums.DegradeState;
import com.shop.rec.mapper.RecConfigMapper;
import com.shop.rec.model.dto.ConfigItemDTO;
import com.shop.rec.model.entity.RecConfigDO;
import com.shop.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 推荐策略配置管理（M-09）
 *
 * <p>
 * 严格对应《详细设计说明书》3.9：权限校验（由接口层承担）→ 格式校验 → 组合校验 → 落库 →
 * 同步内存库副本并递增版本号 → 各模块按版本号热更新（默认 30 秒内生效）。
 * 配置的权威来源是推荐库表，内存库副本供各模块读取。
 *
 * <p>
 * <b>校验不通过即拒绝写入并保留旧值</b>：非法配置一旦生效会影响线上推荐结果，
 * 因此校验失败直接抛业务异常（错误码 4001），不做部分写入。
 *
 * <p>
 * <b>强制降级开关（degrade.force）为高危项</b>：除权限分离外，此处实现「强制过期 + 持续告警」
 * ——写入时记录失效时间并启动自动复位，生效期间每轮复位检查都会告警。
 * 缺少该防护时，一次演练后的遗忘就会造成全站推荐持续降级，而因降级返回的 HTTP 状态仍为 200、
 * 常规监控不会报警（详细设计 3.9 步骤 8）。
 *
 * @author shop
 */
@Slf4j
@Service
@Profile("rec")
@RequiredArgsConstructor
public class ConfigManageService {

    /** 强制降级开关的默认生效时长（分钟）：到期自动复位 */
    private static final long FORCED_DEGRADE_MINUTES = 30L;

    /** 组合校验：窗口权值配置键 */
    private static final List<String> WINDOW_WEIGHT_KEYS = List.of(
            RecConfigKeys.WEIGHT_WINDOW_1MIN, RecConfigKeys.WEIGHT_WINDOW_5MIN,
            RecConfigKeys.WEIGHT_WINDOW_10MIN, RecConfigKeys.WEIGHT_WINDOW_1H,
            RecConfigKeys.WEIGHT_WINDOW_24H, RecConfigKeys.WEIGHT_WINDOW_7D);

    /** 组合校验：打分权值配置键 */
    private static final List<String> RANK_WEIGHT_KEYS = List.of(
            RecConfigKeys.RANK_WEIGHT_CAT, RecConfigKeys.RANK_WEIGHT_HOT, RecConfigKeys.RANK_WEIGHT_BRAND);

    /** 强制降级开关的失效时间键 */
    private static final String KEY_FORCED_DEGRADE_EXPIRE_AT = "rec:degrade:force:expireAt";

    private final RecConfigMapper recConfigMapper;

    private final RecRedis recRedis;

    /**
     * 查询配置项
     *
     * @param groupName 配置分组，为空时返回全部
     * @return 配置项列表
     */
    public List<RecConfigDO> listConfigs(String groupName) {
        LambdaQueryWrapper<RecConfigDO> wrapper = new LambdaQueryWrapper<RecConfigDO>()
                .orderByAsc(RecConfigDO::getGroupName)
                .orderByAsc(RecConfigDO::getConfigKey);
        if (groupName != null && !groupName.isBlank()) {
            wrapper.eq(RecConfigDO::getGroupName, groupName);
        }
        return recConfigMapper.selectList(wrapper);
    }

    /**
     * 批量更新配置
     *
     * <p>更新范围仅限本次提交的配置键，未提交的键保持原值——避免运营只改一项时其余项被意外重置。
     *
     * @param items    配置项列表
     * @param operator 操作人账号
     * @return 递增后的配置版本号
     * @throws BusinessException 校验不通过时抛出（错误码 4001），不写入任何配置
     */
    @Transactional(rollbackFor = Exception.class, transactionManager = "recTransactionManager")
    public long updateConfigs(List<ConfigItemDTO> items, String operator) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(RecErrorCode.CONFIG_INVALID, "配置项不能为空");
        }

        // 以「现有配置 + 本次提交」的合并视图做组合校验，保证跨次提交也能守住约束
        Map<String, String> merged = currentConfigMap();
        for (ConfigItemDTO item : items) {
            validateFormat(item);
            merged.put(item.getConfigKey(), item.getConfigValue());
        }
        validateCombination(merged);

        LocalDateTime now = LocalDateTime.now();
        for (ConfigItemDTO item : items) {
            RecConfigDO existing = recConfigMapper.selectOne(new LambdaQueryWrapper<RecConfigDO>()
                    .eq(RecConfigDO::getConfigKey, item.getConfigKey()));
            RecConfigDO record = existing == null ? new RecConfigDO() : existing;
            record.setConfigKey(item.getConfigKey());
            record.setConfigValue(item.getConfigValue());
            record.setValueType(item.getValueType() != null ? item.getValueType()
                    : (existing != null ? existing.getValueType() : "string"));
            record.setGroupName(existing != null ? existing.getGroupName() : resolveGroup(item.getConfigKey()));
            record.setOperator(operator);
            record.setUpdateTime(now);
            if (existing == null) {
                recConfigMapper.insert(record);
            } else {
                recConfigMapper.updateById(record);
            }
            recRedis.set(RecRedisKeys.cfg(item.getConfigKey()), item.getConfigValue());
        }

        // 高危项特殊处理：记录失效时间，到期自动复位
        applyForcedDegradeGuard(merged);

        long version = nextConfigVersion();
        log.info("推荐配置已更新, operator: {}, 条目数: {}, 新版本号: {}", operator, items.size(), version);
        return version;
    }

    /**
     * 强制降级开关的自动复位检查
     *
     * <p>每分钟检查一次：超过失效时间即复位为「正常」。这是「三重防护」中的强制过期环节。
     */
    @Scheduled(cron = "0 * * * * ?")
    public void checkForcedDegradeExpiry() {
        try {
            String forcedState = recRedis.get(RecRedisKeys.cfg(RecConfigKeys.DEGRADE_FORCE));
            if (forcedState == null || DegradeState.NORMAL.getText().equals(forcedState)) {
                return;
            }
            String expireAt = recRedis.get(KEY_FORCED_DEGRADE_EXPIRE_AT);
            long expireMillis = expireAt == null ? 0L : parseLong(expireAt);
            if (expireMillis > 0 && System.currentTimeMillis() >= expireMillis) {
                RecConfigDO record = recConfigMapper.selectOne(new LambdaQueryWrapper<RecConfigDO>()
                        .eq(RecConfigDO::getConfigKey, RecConfigKeys.DEGRADE_FORCE));
                if (record != null) {
                    record.setConfigValue(DegradeState.NORMAL.getText());
                    record.setOperator("system-auto-reset");
                    recConfigMapper.updateById(record);
                }
                recRedis.set(RecRedisKeys.cfg(RecConfigKeys.DEGRADE_FORCE), DegradeState.NORMAL.getText());
                recRedis.delete(KEY_FORCED_DEGRADE_EXPIRE_AT);
                nextConfigVersion();
                log.warn("强制降级开关到期已自动复位为「正常」，原状态: {}", forcedState);
            } else {
                // 生效期间持续告警：降级返回的 HTTP 状态为 200，仅靠常规监控无法发现
                log.warn("强制降级开关处于生效状态，当前值: {}, 失效时间戳: {}", forcedState, expireMillis);
            }
        } catch (Exception e) {
            log.error("强制降级开关复位检查失败", e);
        }
    }

    /**
     * 读取当前全部配置
     *
     * @return 配置键到配置值的映射
     */
    public Map<String, String> currentConfigMap() {
        return recConfigMapper.selectList(null).stream()
                .collect(Collectors.toMap(RecConfigDO::getConfigKey, RecConfigDO::getConfigValue, (a, b) -> b));
    }

    /**
     * 格式校验：类型与取值区间
     *
     * @param item 配置项
     * @throws BusinessException 校验不通过时抛出
     */
    private void validateFormat(ConfigItemDTO item) {
        String key = item.getConfigKey();
        String value = item.getConfigValue();
        if (!RecConfigKeys.DEFAULTS.containsKey(key)) {
            throw new BusinessException(RecErrorCode.CONFIG_INVALID, "未知配置键: " + key);
        }
        String type = item.getValueType();
        if (type == null) {
            type = inferType(key);
        }
        try {
            switch (type) {
                case "int":
                    Integer.parseInt(value.trim());
                    break;
                case "decimal":
                    double parsed = Double.parseDouble(value.trim());
                    if (parsed < 0D) {
                        throw new BusinessException(RecErrorCode.CONFIG_INVALID, key + " 不能为负数");
                    }
                    break;
                case "json":
                case "string":
                default:
                    if (value.isBlank()) {
                        throw new BusinessException(RecErrorCode.CONFIG_INVALID, key + " 不能为空");
                    }
                    break;
            }
        } catch (NumberFormatException e) {
            throw new BusinessException(RecErrorCode.CONFIG_INVALID, key + " 取值类型不合法: " + value);
        }
        if (RecConfigKeys.DEGRADE_FORCE.equals(key) && DegradeState.fromText(value) == null) {
            throw new BusinessException(RecErrorCode.CONFIG_INVALID, "强制降级状态取值非法: " + value);
        }
    }

    /**
     * 组合校验：跨配置项的约束
     *
     * @param configs 合并后的配置视图
     * @throws BusinessException 校验不通过时抛出
     */
    private void validateCombination(Map<String, String> configs) {
        double windowWeightSum = sumOf(configs, WINDOW_WEIGHT_KEYS);
        if (Math.abs(windowWeightSum - 1.0D) > 0.0001D) {
            throw new BusinessException(RecErrorCode.CONFIG_INVALID,
                    "窗口权值之和必须为 1，当前为 " + windowWeightSum);
        }
        double rankWeightSum = sumOf(configs, RANK_WEIGHT_KEYS);
        if (Math.abs(rankWeightSum - 1.0D) > 0.0001D) {
            throw new BusinessException(RecErrorCode.CONFIG_INVALID,
                    "打分权值之和必须为 1，当前为 " + rankWeightSum);
        }
        String ratio = configs.get(RecConfigKeys.RECALL_RATIO);
        if (ratio != null) {
            String[] parts = ratio.split(":");
            if (parts.length != 2) {
                throw new BusinessException(RecErrorCode.CONFIG_INVALID, "召回比例格式应为「类目:热门」");
            }
            try {
                int sum = Integer.parseInt(parts[0].trim()) + Integer.parseInt(parts[1].trim());
                if (sum != 100) {
                    throw new BusinessException(RecErrorCode.CONFIG_INVALID, "召回比例两项之和必须为 100，当前为 " + sum);
                }
            } catch (NumberFormatException e) {
                throw new BusinessException(RecErrorCode.CONFIG_INVALID, "召回比例取值不合法: " + ratio);
            }
        }
    }

    /**
     * 强制降级开关的写入保护
     *
     * @param configs 合并后的配置视图
     */
    private void applyForcedDegradeGuard(Map<String, String> configs) {
        String forced = configs.get(RecConfigKeys.DEGRADE_FORCE);
        if (forced == null || DegradeState.NORMAL.getText().equals(forced)) {
            recRedis.delete(KEY_FORCED_DEGRADE_EXPIRE_AT);
            return;
        }
        long expireAt = System.currentTimeMillis() + FORCED_DEGRADE_MINUTES * 60_000L;
        recRedis.set(KEY_FORCED_DEGRADE_EXPIRE_AT, String.valueOf(expireAt));
        log.warn("强制降级开关已生效: {}, 将在 {} 分钟后自动复位，期间每次请求都会记录告警",
                forced, FORCED_DEGRADE_MINUTES);
    }

    /**
     * 递增配置版本号
     *
     * <p>各模块每 30 秒比对该版本号，发现变化即重载配置（详细设计 3.9 步骤 6）。
     *
     * @return 递增后的版本号
     */
    private long nextConfigVersion() {
        long version = recRedis.increment(RecRedisKeys.CFG_VERSION, 1L);
        return version;
    }

    /**
     * 解析配置键所属分组
     *
     * @param configKey 配置键
     * @return 分组名
     */
    private String resolveGroup(String configKey) {
        if (configKey.startsWith("weight.behavior")) {
            return "行为权值";
        }
        if (configKey.startsWith("weight.window")) {
            return "窗口权值";
        }
        if (configKey.startsWith("recall.")) {
            return "召回";
        }
        if (configKey.startsWith("rank.")) {
            return "打分";
        }
        if (configKey.startsWith("cache.")) {
            return "缓存";
        }
        if (configKey.startsWith("collect.") || configKey.startsWith("limit.")) {
            return "采集防护";
        }
        if (configKey.startsWith("exclude.")) {
            return "过滤";
        }
        if (RecConfigKeys.DEGRADE_FORCE.equals(configKey)) {
            return "开关";
        }
        return "特征计算";
    }

    /**
     * 推断配置值的类型
     *
     * @param configKey 配置键
     * @return 类型名
     */
    private String inferType(String configKey) {
        String defaultValue = RecConfigKeys.DEFAULTS.get(configKey);
        if (defaultValue == null) {
            return "string";
        }
        try {
            Integer.parseInt(defaultValue);
            return "int";
        } catch (NumberFormatException ignored) {
            // 继续尝试小数
        }
        try {
            Double.parseDouble(defaultValue);
            return "decimal";
        } catch (NumberFormatException ignored) {
            return "string";
        }
    }

    /**
     * 求指定配置键的数值之和
     *
     * @param configs 配置视图
     * @param keys    配置键列表
     * @return 求和结果；缺项按 0 计
     */
    private double sumOf(Map<String, String> configs, List<String> keys) {
        double sum = 0D;
        for (String key : keys) {
            String value = configs.get(key);
            if (value == null) {
                value = RecConfigKeys.DEFAULTS.get(key);
            }
            if (value == null) {
                continue;
            }
            try {
                sum += Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                throw new BusinessException(RecErrorCode.CONFIG_INVALID, key + " 取值不合法: " + value);
            }
        }
        return sum;
    }

    /**
     * 字符串转长整型，解析失败返回 0
     *
     * @param value 原始值
     * @return 数值
     */
    private long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
