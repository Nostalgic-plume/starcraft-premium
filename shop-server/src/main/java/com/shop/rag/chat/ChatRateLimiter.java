package com.shop.rag.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 客服接口频率限制（接入层守卫）
 *
 * <p>
 * 按「用户 + 分钟」计数，防止脚本刷调用拖垮模型与检索链路。
 * Redis INCR 天然原子，首次计数时设置 2 分钟过期（留出一分钟窗口的边界余量）。
 * </p>
 *
 * @author shop
 */
@Component
@RequiredArgsConstructor
public class ChatRateLimiter {

    private static final String KEY_PREFIX = "chat:rate:";

    private final RedisTemplate<String, String> redisTemplate;

    /** 单用户每分钟允许的消息数；≤0 表示关闭限流 */
    @Value("${rag.chat.rate-limit-per-minute:20}")
    private int limitPerMinute;

    /**
     * 是否放行本次请求
     *
     * @param userId 当前登录用户
     * @return true=放行；false=超出频率
     */
    public boolean allow(Long userId) {
        if (limitPerMinute <= 0) {
            return true;
        }
        long minuteBucket = System.currentTimeMillis() / 60_000;
        String key = KEY_PREFIX + userId + ":" + minuteBucket;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, 2, TimeUnit.MINUTES);
            }
            return count == null || count <= limitPerMinute;
        } catch (Exception e) {
            // Redis 故障不阻塞客服主流程：限流是保护措施，不是正确性依赖
            return true;
        }
    }
}
