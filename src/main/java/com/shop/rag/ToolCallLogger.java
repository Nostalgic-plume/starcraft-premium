package com.shop.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 工具调用日志（可观测性，Phase 5 T6）
 * <p>
 * 异步记录每次工具调用的元数据（userId / 工具名 / 耗时 / 成败），用于可观测性与审计。
 * 安全约束：不记录 LLM 原始参数、不记录工具完整结果（含订单号等 PII），只记脱敏元数据；
 * 异步执行 + 吞异常，绝不阻塞对话主链路。
 *
 * @author shop
 */
@Slf4j
@Component
public class ToolCallLogger {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 记录一次工具调用
     *
     * @param userId       用户ID
     * @param toolName     工具名
     * @param durationMs   耗时（ms）
     * @param success      是否成功
     * @param resultSummary 脱敏结果摘要（可选；建议传 null 或脱敏后的摘要，避免含订单号等 PII）
     */
    public void log(Long userId, String toolName, long durationMs, boolean success, String resultSummary) {
        executor.execute(() -> {
            try {
                log.info("工具调用日志 | userId={} | tool={} | 耗时={}ms | success={} | result={}",
                        userId, toolName, durationMs, success, truncate(resultSummary, 100));
            } catch (Exception e) {
                // 日志失败不影响主链路
            }
        });
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
