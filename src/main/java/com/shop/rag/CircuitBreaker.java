package com.shop.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 简单熔断器（不引第三方组件，Phase 5 系统韧性）
 * <p>
 * 保护主对话模型（DeepSeek）：连续失败达阈值 → 熔断打开，熔断期内不再调用主模型；
 * TTL 后进入半开（允许一次重试），重试成功则关闭并重置计数，失败则重新熔断计时。
 * <p>
 * 只保护生成层（对话模型调用），不影响检索层（向量/关键字检索不依赖对话模型）。
 * 挂载点：ChatService 的 DeepSeek onError → onFailure()，onCompleteResponse → onSuccess()。
 *
 * @author shop
 */
@Slf4j
@Component
public class CircuitBreaker {

    /** 连续失败阈值 */
    private static final int FAILURE_THRESHOLD = 3;
    /** 熔断时长（ms） */
    private static final long OPEN_TIMEOUT_MS = 30_000L;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile boolean open = false;
    private volatile long openedAt = 0L;

    /**
     * 是否应绕过主模型（熔断中）
     * 熔断且未过 TTL → true（调用方走降级）；已过 TTL → 半开，返回 false 允许一次重试
     */
    public boolean isOpen() {
        if (!open) {
            return false;
        }
        if (System.currentTimeMillis() - openedAt >= OPEN_TIMEOUT_MS) {
            return false; // 半开：放行重试（重试结果由 onSuccess/onFailure 决定）
        }
        return true;
    }

    /** 记录一次失败：达阈值触发熔断；半开重试失败则重新计时 */
    public void onFailure() {
        int fails = consecutiveFailures.incrementAndGet();
        if (fails >= FAILURE_THRESHOLD) {
            open = true;
            openedAt = System.currentTimeMillis();
            log.warn("熔断器触发：连续失败 {} 次，熔断 {}ms", fails, OPEN_TIMEOUT_MS);
        } else if (open) {
            openedAt = System.currentTimeMillis(); // 半开重试失败，重新计时
        }
    }

    /** 记录一次成功：重置计数并关闭熔断 */
    public void onSuccess() {
        consecutiveFailures.set(0);
        if (open) {
            log.info("熔断器恢复：主模型调用成功");
        }
        open = false;
        openedAt = 0L;
    }
}
