package com.shop.rec.profile;

import com.shop.rec.common.RecRedis;
import com.shop.rec.constant.BehaviorTypes;
import com.shop.rec.constant.RecConfigKeys;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.mall.mapper.MallProductMapper;
import com.shop.rec.mall.model.MallProductDO;
import com.shop.rec.mapper.UserBehaviorLogMapper;
import com.shop.rec.mapper.UserProfileMapper;
import com.shop.rec.model.entity.UserBehaviorLogDO;
import com.shop.rec.model.entity.UserProfileDO;
import com.shop.rec.model.vo.PrefItemVO;
import com.shop.rec.service.RecConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 用户画像生成与版本回滚（M-03）
 *
 * <p>
 * 严格对应《详细设计说明书》3.3：每日业务低峰期汇聚用户近 30 天行为，按「用户 + 二级类目」
 * 与「用户 + 品牌」累计加权分值，取前 5 名生成带版本号的长期画像；生成失败时对外仍使用
 * 上一有效版本。事务操作集中在 {@link UserProfileWriter}。
 *
 * <p>
 * <b>权值口径</b>：与实时特征计算一致，取自配置项 weight.behavior.*（详细设计 3.3 输入约定），
 * 保证画像与实时特征的偏好强度可比。
 *
 * <p>
 * <b>冷启动</b>：行为条数少于 3 条的用户不生成画像，视为冷启动用户并计数。
 *
 * <p>
 * <b>两条触发路径</b>：定时任务（每日 02:00）与管理后台手动触发，二者共用
 * {@link #doGenerate()}，保证产出一致；执行期间由 {@link #rebuilding} 串行化，
 * 避免两次任务交叠互相覆盖版本与有效性。
 *
 * @author shop
 */
@Slf4j
@Service
@Profile("rec")
@RequiredArgsConstructor
public class UserProfileService {

    /** 用户分片数（对应详细设计 3.3 步骤 2「按用户标识取模分为 8 片」） */
    private static final int SHARDS = 8;

    /** 生成画像所需的最少行为条数，低于该值视为冷启动用户 */
    private static final int MIN_BEHAVIOR_COUNT = 3;

    /** 偏好维度取值个数 */
    private static final int PREF_TOP_N = 5;

    /** 画像计算取用的行为天数 */
    private static final int PROFILE_DAYS = 30;

    /** 单次任务最长执行时间（毫秒），超出即中断并保留旧版本（详细设计 3.3 步骤：单次 ≤10 分钟） */
    private static final long MAX_DURATION_MS = 10 * 60 * 1000L;

    /** 商品维度加载的批次大小 */
    private static final int LOAD_BATCH_SIZE = 500;

    /** 版本号格式：日期 */
    private static final DateTimeFormatter VERSION_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final UserBehaviorLogMapper behaviorLogMapper;

    private final UserProfileMapper userProfileMapper;

    private final MallProductMapper mallProductMapper;

    private final RecConfigService configService;

    private final UserProfileWriter profileWriter;

    private final RecRedis recRedis;

    /** 未执行过重建时的状态说明（与前端约定：该文案表示「从未重建」，而非「失败」） */
    public static final String NOT_REBUILT = "尚未执行重建";

    /**
     * 画像任务进行中标记
     *
     * <p>手动触发与定时任务共用：两次任务交叠会互相覆盖写入的版本与有效性，
     * 必须串行。
     */
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    /** 最近一次画像任务的结束时间；从未执行时为 null */
    private volatile LocalDateTime lastRebuildTime;

    /** 最近一次画像任务是否成功 */
    private volatile boolean lastRebuildSuccess = false;

    /** 最近一次画像任务的结果说明 */
    private volatile String lastRebuildMessage = NOT_REBUILT;

    /**
     * 每日画像生成
     *
     * <p>每日 02:00 执行（对应详细设计 8.2 任务 1），与商品全量重建（01:00）分时执行避免重叠。
     * 单用户失败跳过并计数，整体超时中断并保留旧版本——旧版本在写入新版本前始终有效。
     *
     * <p>与手动触发（{@link #triggerRebuild()}）走同一条执行路径，保证两者产出一致。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void generateDailyProfile() {
        if (!triggerRebuild()) {
            log.warn("已有画像任务在执行中，本次定时触发被忽略");
        }
    }

    /**
     * 手动触发画像重建（管理后台用）
     *
     * <p><b>为什么需要这个入口</b>：画像原先只依赖 {@code @Scheduled}（每日 02:00），
     * 换完用户与行为数据只能干等次日凌晨；手动触发补齐了这个缺口。
     *
     * <p><b>异步执行</b>：全量行为归纳耗时可达数分钟，同步执行会让管理后台请求超时。
     * 触发后由调用方轮询 {@link #rebuildStatus()} 获知结果。
     *
     * @return 已触发返回 true；已有任务在执行中返回 false
     */
    public boolean triggerRebuild() {
        if (!rebuilding.compareAndSet(false, true)) {
            return false;
        }
        Thread worker = new Thread(() -> {
            try {
                doGenerate();
            } finally {
                rebuilding.set(false);
            }
        }, "rec-profile-rebuild");
        // 守护线程：重建不应阻止进程退出
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /**
     * 查询最近一次画像任务的状态
     *
     * @return 任务状态快照
     */
    public RebuildStatus rebuildStatus() {
        return new RebuildStatus(rebuilding.get(), lastRebuildTime,
                lastRebuildSuccess, lastRebuildMessage);
    }

    /**
     * 执行一次画像生成（手动触发与定时任务共用）
     */
    private void doGenerate() {
        long start = System.currentTimeMillis();
        int version = Integer.parseInt(LocalDateTime.now().format(VERSION_FORMAT));
        LocalDateTime since = LocalDateTime.now().minusDays(PROFILE_DAYS);

        Map<Long, MallProductDO> itemDimensions = loadItemDimensions();
        if (itemDimensions.isEmpty()) {
            log.warn("商品维度为空，画像任务终止并保留旧版本");
            markFinished(false, "商品维度为空，任务终止并保留旧版本");
            return;
        }

        int success = 0;
        int coldStart = 0;
        int failed = 0;
        boolean timeout = false;

        for (int shard = 0; shard < SHARDS; shard++) {
            if (System.currentTimeMillis() - start > MAX_DURATION_MS) {
                log.error("画像任务超时中断（已处理 {} 个分片），保留旧版本，已成功: {}, 冷启动: {}, 失败: {}",
                        shard, success, coldStart, failed);
                timeout = true;
                break;
            }

            List<UserBehaviorLogDO> shardLogs = behaviorLogMapper.selectForProfile(since, SHARDS, shard);
            Map<Long, List<UserBehaviorLogDO>> byUser = new LinkedHashMap<>();
            for (UserBehaviorLogDO behaviorLog : shardLogs) {
                if (behaviorLog.getUserId() == null) {
                    continue;
                }
                byUser.computeIfAbsent(behaviorLog.getUserId(), k -> new ArrayList<>()).add(behaviorLog);
            }

            for (Map.Entry<Long, List<UserBehaviorLogDO>> entry : byUser.entrySet()) {
                List<UserBehaviorLogDO> userLogs = entry.getValue();
                if (userLogs.size() < MIN_BEHAVIOR_COUNT) {
                    coldStart++;
                    continue;
                }
                try {
                    saveUserProfile(entry.getKey(), userLogs, itemDimensions, version);
                    success++;
                } catch (Exception e) {
                    // 单用户失败不中断整体任务（详细设计 3.3 异常分支）
                    failed++;
                    log.warn("用户画像生成失败已跳过, userId: {}", entry.getKey(), e);
                }
            }
        }

        log.info("画像任务完成, version: {}, 成功: {}, 冷启动跳过: {}, 失败: {}, 耗时: {}ms",
                version, success, coldStart, failed, System.currentTimeMillis() - start);

        if (timeout) {
            markFinished(false, String.format("任务超时中断，已生成 %d 个（保留旧版本）", success));
        } else if (success == 0) {
            // 跑完但一条都没生成，与「商品为空」同理属需要关注的失败态：
            // 只看时间戳的调用方会以为「重建完成」，实际画像仍是空的
            markFinished(false,
                    String.format("未生成任何画像（行为数据不足 %d 条的用户）", MIN_BEHAVIOR_COUNT));
        } else {
            markFinished(true, String.format("画像生成完成：成功 %d，冷启动跳过 %d，失败 %d",
                    success, coldStart, failed));
        }
    }

    /**
     * 记录一次画像任务的结果（结束时间 + 成功标志 + 说明）
     *
     * <p>无论成功失败都更新时间戳（标识「动作已结束」，让前端停止轮询），
     * 成功与否必须靠 {@link #lastRebuildSuccess} 区分。
     *
     * @param success 是否成功
     * @param message 结果说明
     */
    private void markFinished(boolean success, String message) {
        this.lastRebuildSuccess = success;
        this.lastRebuildMessage = message;
        this.lastRebuildTime = LocalDateTime.now();
    }

    /**
     * 画像任务状态快照（管理后台轮询用）
     *
     * @param rebuilding         是否正在执行
     * @param lastRebuildTime    最近一次任务的结束时间；从未执行时为 null
     * @param lastRebuildSuccess 最近一次任务是否成功
     * @param lastRebuildMessage 结果说明；从未执行时为「尚未执行重建」
     */
    public record RebuildStatus(boolean rebuilding, LocalDateTime lastRebuildTime,
                                boolean lastRebuildSuccess, String lastRebuildMessage) {
    }

    /**
     * 画像版本回滚（外部接口 E-08 的业务实现）
     *
     * <p>校验目标版本仍在保留范围内后切换有效性并清理画像缓存，
     * 回滚后下一个查询请求即按目标版本提供偏好数据（详细设计 3.3 步骤 9）。
     *
     * @param userId        用户标识
     * @param targetVersion 目标版本号
     * @throws IllegalArgumentException 目标版本不可用时抛出，由调用方转换为错误码 4004
     */
    public void rollbackProfile(Long userId, Integer targetVersion) {
        if (targetVersion == null) {
            throw new IllegalArgumentException("目标版本号不能为空");
        }
        profileWriter.rollback(userId, targetVersion);
        // 清理画像缓存：本工程画像直接从推荐库读取，此处清理为预留（后续若加画像缓存键需同步清理）
        recRedis.delete(RecRedisKeys.userFeatMeta(userId));
        log.info("画像版本回滚完成, userId: {}, targetVersion: {}", userId, targetVersion);
    }

    /**
     * 查询指定用户的全部画像版本（外部接口 E-08 的列表入口）
     *
     * <p>回滚必须先能看到有哪些版本可选：只有回滚接口而无列表接口时，运营无从得知目标版本号，
     * 「支持回滚」这一要求在界面上根本落不了地。
     *
     * @param userId 用户标识
     * @return 画像版本列表（按版本号降序，含各版本的偏好内容）；无版本时返回空列表
     */
    public List<UserProfileDO> listVersions(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return userProfileMapper.selectVersionsByUserId(userId);
    }

    /**
     * 生成单个用户的画像
     *
     * @param userId         用户标识
     * @param userLogs       该用户近 30 天行为
     * @param itemDimensions 商品标识到商品维度的映射
     * @param version        版本号
     */
    private void saveUserProfile(Long userId, List<UserBehaviorLogDO> userLogs,
                                 Map<Long, MallProductDO> itemDimensions, int version) {
        Map<Long, Double> categoryScores = new HashMap<>();
        Map<Long, Double> brandScores = new HashMap<>();

        for (UserBehaviorLogDO behaviorLog : userLogs) {
            double score = behaviorScore(behaviorLog.getEventType());
            if (score <= 0D) {
                continue;
            }
            MallProductDO dimension = itemDimensions.get(behaviorLog.getItemId());
            if (dimension == null) {
                continue;
            }
            if (dimension.getCategoryId() != null && dimension.getCategoryId() > 0L) {
                categoryScores.merge(dimension.getCategoryId(), score, Double::sum);
            }
            if (dimension.getBrandId() != null && dimension.getBrandId() > 0L) {
                brandScores.merge(dimension.getBrandId(), score, Double::sum);
            }
        }

        profileWriter.saveVersion(userId, version,
                topN(categoryScores, PREF_TOP_N),
                topN(brandScores, PREF_TOP_N),
                userLogs.size());
    }

    /**
     * 取分值最高的前 N 个维度
     *
     * @param scores 维度取值到累计分值的映射
     * @param n      取用个数
     * @return 偏好项列表（按分值降序）
     */
    private List<PrefItemVO> topN(Map<Long, Double> scores, int n) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(n)
                .map(entry -> new PrefItemVO(entry.getKey(), BigDecimal.valueOf(entry.getValue())))
                .collect(Collectors.toList());
    }

    /**
     * 查询行为类型对应的分值（口径与实时特征一致，取自配置）
     *
     * @param eventType 行为类型
     * @return 分值；未知类型返回 0（不参与累计）
     */
    private double behaviorScore(String eventType) {
        if (eventType == null) {
            return 0D;
        }
        switch (eventType) {
            case BehaviorTypes.VIEW:
                return configService.getDouble(RecConfigKeys.WEIGHT_BEHAVIOR_VIEW);
            case BehaviorTypes.ADD_CART:
                return configService.getDouble(RecConfigKeys.WEIGHT_BEHAVIOR_ADD_CART);
            case BehaviorTypes.FAVORITE:
                return configService.getDouble(RecConfigKeys.WEIGHT_BEHAVIOR_FAVORITE);
            case BehaviorTypes.PURCHASE:
                return configService.getDouble(RecConfigKeys.WEIGHT_BEHAVIOR_PURCHASE);
            default:
                return 0D;
        }
    }

    /**
     * 加载商品维度映射（商品标识 → 类目 / 品牌）
     *
     * <p>分批读取商城商品表，构建内存映射供画像归纳使用。加载失败时返回空映射，
     * 由调用方终止任务并保留旧版本。
     *
     * @return 商品标识到商品维度的映射
     */
    private Map<Long, MallProductDO> loadItemDimensions() {
        Map<Long, MallProductDO> dimensions = new HashMap<>();
        long lastId = 0L;
        try {
            while (true) {
                List<MallProductDO> batch = mallProductMapper.selectBatchAfterId(lastId, LOAD_BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }
                for (MallProductDO product : batch) {
                    lastId = Math.max(lastId, product.getId());
                    dimensions.put(product.getId(), product);
                }
            }
        } catch (Exception e) {
            log.error("商品维度加载失败，画像任务将终止并保留旧版本", e);
            return new HashMap<>();
        }
        return dimensions;
    }
}
