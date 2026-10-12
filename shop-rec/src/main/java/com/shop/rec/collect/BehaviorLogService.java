package com.shop.rec.collect;

import com.shop.rec.mapper.UserBehaviorLogMapper;
import com.shop.rec.model.entity.UserBehaviorLogDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 行为明细异步落库（M-01 步骤 9）
 *
 * <p>
 * 上报接口不等待落库完成：事件先进入内存缓冲，达到批量阈值（200 条）或定时器到期（500 毫秒）
 * 时批量写入 {@code t_user_behavior_log}，保证接口在 100ms 内返回（对应详细设计 3.1 步骤 9、10）。
 *
 * <p>
 * <b>失败语义</b>：批量写入重试 2 次仍失败时，将该批数据写入本地补偿文件并告警，
 * 不回滚、不阻塞主流程（对应详细设计 3.1 异常分支「异步落库失败」）。
 * 应用关闭时由 {@code @PreDestroy} 冲刷剩余缓冲，避免丢数据。
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorLogService {

    /** 批量写入阈值：缓冲达到该条数立即触发落库 */
    private static final int BATCH_SIZE = 200;

    /** 单批写入的失败重试次数 */
    private static final int MAX_RETRY = 2;

    /** 补偿文件目录（相对工程根，已由 .gitignore 忽略 logs/） */
    private static final Path COMPENSATION_DIR = Path.of("logs", "rec-compensation");

    /** 补偿文件名的时间格式 */
    private static final DateTimeFormatter FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final UserBehaviorLogMapper userBehaviorLogMapper;

    /** 待落库缓冲：上报线程只做入队，落库由独立线程完成 */
    private final Queue<UserBehaviorLogDO> buffer = new ConcurrentLinkedQueue<>();

    /** 当前缓冲条数，避免每次遍历队列统计 */
    private final AtomicInteger bufferedCount = new AtomicInteger();

    /** 落库线程：IO 密集，使用虚拟线程（工程编译目标 Java 21，已启用虚拟线程） */
    private final ExecutorService flushExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 追加待落库的行为明细（非阻塞）
     *
     * @param behaviorLog 行为明细
     */
    public void append(UserBehaviorLogDO behaviorLog) {
        buffer.offer(behaviorLog);
        if (bufferedCount.incrementAndGet() >= BATCH_SIZE) {
            flushAsync();
        }
    }

    /**
     * 定时冲刷：保证低频场景下缓冲不被长时间滞留
     */
    @Scheduled(fixedDelay = 500L)
    public void flushByTimer() {
        flush();
    }

    /**
     * 异步冲刷（达到批量阈值时触发）
     */
    public void flushAsync() {
        flushExecutor.submit(this::flush);
    }

    /**
     * 冲刷剩余缓冲（应用关闭前调用）
     */
    @jakarta.annotation.PreDestroy
    public void flushOnShutdown() {
        flush();
        flushExecutor.shutdown();
    }

    /**
     * 批量落库
     *
     * <p>加锁保证同一时刻只有一次冲刷在执行，避免并发下重复取用同一批数据。
     */
    private synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<UserBehaviorLogDO> batch = new ArrayList<>(BATCH_SIZE);
        UserBehaviorLogDO item;
        while (batch.size() < BATCH_SIZE && (item = buffer.poll()) != null) {
            batch.add(item);
        }
        bufferedCount.addAndGet(-batch.size());
        if (batch.isEmpty()) {
            return;
        }

        for (int attempt = 1; attempt <= MAX_RETRY + 1; attempt++) {
            try {
                for (UserBehaviorLogDO behaviorLog : batch) {
                    userBehaviorLogMapper.insert(behaviorLog);
                }
                return;
            } catch (Exception e) {
                if (attempt > MAX_RETRY) {
                    log.error("行为明细落库失败，重试 {} 次后放弃，转写补偿文件, 批次大小: {}", MAX_RETRY, batch.size(), e);
                    writeCompensation(batch);
                } else {
                    log.warn("行为明细落库失败，第 {} 次重试, 批次大小: {}", attempt, batch.size(), e);
                }
            }
        }
    }

    /**
     * 写本地补偿文件
     *
     * <p>落库持续失败时保证数据不丢，供后续人工或补偿任务补录。
     *
     * @param batch 落库失败的批次
     */
    private void writeCompensation(List<UserBehaviorLogDO> batch) {
        String fileName = "behavior-" + LocalDateTime.now().format(FILE_TIME_FORMAT) + ".json";
        try {
            Files.createDirectories(COMPENSATION_DIR);
            StringBuilder content = new StringBuilder();
            for (UserBehaviorLogDO behaviorLog : batch) {
                content.append(behaviorLog.getEventId()).append('|')
                        .append(behaviorLog.getUserId()).append('|')
                        .append(behaviorLog.getItemId()).append('|')
                        .append(behaviorLog.getEventType()).append('|')
                        .append(behaviorLog.getEventTime()).append(System.lineSeparator());
            }
            Files.writeString(COMPENSATION_DIR.resolve(fileName), content.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.error("已写入行为补偿文件: {}, 条数: {}", COMPENSATION_DIR.resolve(fileName).toAbsolutePath(), batch.size());
        } catch (IOException ioException) {
            log.error("行为补偿文件写入失败，数据丢失, 条数: {}", batch.size(), ioException);
        }
    }
}
