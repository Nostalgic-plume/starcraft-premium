package com.shop.rec.archive;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.rec.mapper.ItemHotScoreMapper;
import com.shop.rec.mapper.PipelineMetricMapper;
import com.shop.rec.mapper.UserBehaviorLogMapper;
import com.shop.rec.model.entity.ItemHotScoreDO;
import com.shop.rec.model.entity.PipelineMetricDO;
import com.shop.rec.model.entity.UserBehaviorLogDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 数据归档与清理（M-04 的运维任务）
 *
 * <p>
 * 对应《详细设计说明书》4.4 数据归档与清理规则，每日 03:30 执行，清理顺序固定为：
 * 行为明细 → 链路指标 → 商品热度快照 → 补偿文件。<b>任一步骤失败不影响后续步骤</b>。
 *
 * <table>
 *     <tr><th>对象</th><th>保留期限</th></tr>
 *     <tr><td>行为明细 t_user_behavior_log</td><td>90 天</td></tr>
 *     <tr><td>链路指标 t_pipeline_metric</td><td>30 天</td></tr>
 *     <tr><td>商品热度快照 t_item_hot_score</td><td>30 天</td></tr>
 *     <tr><td>行为落库补偿文件</td><td>7 天</td></tr>
 * </table>
 *
 * <p>
 * 其余在线数据的清理由存储自身的有效期承担，不在本任务内：偏好特征、热度榜、类目索引、
 * 商品状态缓存、榜单快照、上报去重与限频键均设置了 TTL 自动过期；Flink 分桶状态由状态 TTL 清理。
 *
 * <p>
 * <b>与设计的一处简化</b>：设计写的是「行为明细按月归档至归档表后删除源数据」。
 * 本项目为演示环境，此处直接删除超期数据，不做归档表落盘——归档的目的是长期留存与合规审计，
 * 演示数据无此诉求。生产环境应在删除前先把待清理区间写入按月归档表。
 *
 * @author shop
 */
@Slf4j
@Service
@Profile("rec")
@RequiredArgsConstructor
public class ArchiveCleanupService {

    /** 行为明细保留天数 */
    private static final int BEHAVIOR_LOG_RETAIN_DAYS = 90;

    /** 链路指标保留天数 */
    private static final int METRIC_RETAIN_DAYS = 30;

    /** 商品热度快照保留天数 */
    private static final int HOT_SCORE_RETAIN_DAYS = 30;

    /** 补偿文件保留天数 */
    private static final int COMPENSATION_FILE_RETAIN_DAYS = 7;

    /** 补偿文件目录（与行为落库组件的约定一致） */
    private static final Path COMPENSATION_DIR = Path.of("logs", "rec-compensation");

    private final UserBehaviorLogMapper userBehaviorLogMapper;

    private final PipelineMetricMapper pipelineMetricMapper;

    private final ItemHotScoreMapper itemHotScoreMapper;

    /**
     * 每日归档清理
     *
     * <p>每日 03:30 执行（对应详细设计 8.2 任务 6），与画像（02:00）、商品重建（01:00）错峰。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanupDaily() {
        long start = System.currentTimeMillis();
        int behaviorRemoved = cleanupBehaviorLog();
        int metricRemoved = cleanupMetric();
        int hotScoreRemoved = cleanupHotScore();
        int fileRemoved = cleanupCompensationFiles();
        log.info("归档清理完成, 行为明细: {}, 指标: {}, 热度快照: {}, 补偿文件: {}, 耗时: {}ms",
                behaviorRemoved, metricRemoved, hotScoreRemoved, fileRemoved, System.currentTimeMillis() - start);
    }

    /**
     * 清理超期行为明细
     *
     * @return 删除条数；失败返回 0（不影响后续步骤）
     */
    private int cleanupBehaviorLog() {
        try {
            LocalDateTime threshold = LocalDateTime.now().minusDays(BEHAVIOR_LOG_RETAIN_DAYS);
            return userBehaviorLogMapper.delete(new LambdaQueryWrapper<UserBehaviorLogDO>()
                    .lt(UserBehaviorLogDO::getEventTime, threshold));
        } catch (Exception e) {
            log.error("行为明细清理失败，次日重试", e);
            return 0;
        }
    }

    /**
     * 清理超期链路指标
     *
     * @return 删除条数；失败返回 0
     */
    private int cleanupMetric() {
        try {
            LocalDateTime threshold = LocalDateTime.now().minusDays(METRIC_RETAIN_DAYS);
            return pipelineMetricMapper.delete(new LambdaQueryWrapper<PipelineMetricDO>()
                    .lt(PipelineMetricDO::getStatTime, threshold));
        } catch (Exception e) {
            log.error("链路指标清理失败，次日重试", e);
            return 0;
        }
    }

    /**
     * 清理超期商品热度快照
     *
     * @return 删除条数；失败返回 0
     */
    private int cleanupHotScore() {
        try {
            LocalDateTime threshold = LocalDateTime.now().minusDays(HOT_SCORE_RETAIN_DAYS);
            return itemHotScoreMapper.delete(new LambdaQueryWrapper<ItemHotScoreDO>()
                    .lt(ItemHotScoreDO::getStatTime, threshold));
        } catch (Exception e) {
            log.error("商品热度快照清理失败，次日重试", e);
            return 0;
        }
    }

    /**
     * 清理超期补偿文件
     *
     * <p>补偿文件由行为落库组件在持续失败时写出（见 {@code BehaviorLogService}），
     * 保留 7 天供人工补录。
     *
     * @return 删除文件数；目录不存在或失败返回 0
     */
    private int cleanupCompensationFiles() {
        if (!Files.exists(COMPENSATION_DIR)) {
            return 0;
        }
        long threshold = System.currentTimeMillis() - COMPENSATION_FILE_RETAIN_DAYS * 24L * 3600 * 1000;
        int removed = 0;
        try (Stream<Path> files = Files.list(COMPENSATION_DIR)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                try {
                    if (Files.getLastModifiedTime(file).toMillis() < threshold) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                } catch (IOException e) {
                    log.warn("补偿文件删除失败，跳过: {}", file, e);
                }
            }
        } catch (IOException e) {
            log.error("补偿文件目录扫描失败，跳过本步骤", e);
            return removed;
        }
        return removed;
    }

    /**
     * 统计待清理的补偿文件（供运维核对）
     *
     * @return 文件数；目录不存在返回 0
     */
    public long countCompensationFiles() {
        if (!Files.exists(COMPENSATION_DIR)) {
            return 0L;
        }
        try (Stream<Path> files = Files.list(COMPENSATION_DIR)) {
            return files.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 清理目录中最旧的文件（保留最近 N 个，供容量兜底）
     *
     * @param keepCount 保留个数
     * @return 删除文件数
     */
    public int trimCompensationFiles(int keepCount) {
        if (!Files.exists(COMPENSATION_DIR)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(COMPENSATION_DIR)) {
            var sorted = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(this::lastModifiedSafely))
                    .toList();
            if (sorted.size() <= keepCount) {
                return 0;
            }
            int removed = 0;
            for (int i = 0; i < sorted.size() - keepCount; i++) {
                Files.deleteIfExists(sorted.get(i));
                removed++;
            }
            return removed;
        } catch (IOException e) {
            log.error("补偿文件容量清理失败", e);
            return 0;
        }
    }

    /**
     * 安全读取文件修改时间
     *
     * @param path 文件路径
     * @return 修改时间毫秒值；读取失败返回 0（视为最旧）
     */
    private long lastModifiedSafely(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
