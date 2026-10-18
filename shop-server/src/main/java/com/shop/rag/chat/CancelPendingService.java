package com.shop.rag.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 取消订单待确认状态（pending）与回复三态判定
 *
 * <p>
 * pending 是「对话层」的待确认标记，不是订单状态：用户提出取消、系统已经问过「确认取消吗」之后，
 * 把目标订单暂存在 Redis，等待用户下一轮明确确认。TTL 5 分钟，过期自动失效。
 * </p>
 *
 * <p>
 * <b>为什么判定必须确定性：</b>确认 / 反悔 / 其他三态不能交给 LLM——用户说「不要取消」时，
 * 意图识别会因为句子里含「取消」而判成取消意图，若再把意图等同于确认，就会直接执行写操作。
 * 因此这里用纯规则判定，且<b>反悔优先于确认</b>。
 * </p>
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelPendingService {

    private static final String KEY_PREFIX = "chat:pending:cancel:";
    private static final long TTL_MINUTES = 5;

    /** 确认词：必须足够明确，避免「好的」「行」这类宽泛回应误触发写操作 */
    private static final String[] CONFIRM_WORDS = {
            "确认取消", "确定取消", "确认退单", "同意取消", "是的取消",
            "确认", "确定", "是的"
    };

    /** 反悔词：优先于确认词判定 */
    private static final String[] REVERSE_WORDS = {
            "不要取消", "不想取消", "不打算取消", "不用取消", "不取消",
            "别取消", "先别", "先不", "暂不", "再想想", "不退了", "算了", "不用了"
    };

    /** 确认句的最大长度：长句多为新请求或追问，不视为确认 */
    private static final int CONFIRM_MAX_LEN = 10;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /** 用户对本轮 pending 的回复类型 */
    public enum Reply {
        /** 明确确认，可以执行取消 */
        CONFIRM,
        /** 明确反悔，清除 pending，不执行 */
        REVERSE,
        /** 其他（追问、转话题、给新订单号等），清除 pending 后走正常链路 */
        OTHER
    }

    /** pending 中暂存的目标订单与归属用户 */
    public record PendingOrder(Long orderId, Long userId) {
    }

    // TODO: 确认交互升级为 SSE `order` 事件 + 前端订单卡片（当前为文本确认话术）

    /**
     * 写入待确认状态（覆盖写，重复调用幂等）
     */
    public void write(String conversationId, Long userId, Long orderId) {
        try {
            String value = objectMapper.writeValueAsString(new PendingOrder(orderId, userId));
            redisTemplate.opsForValue().set(KEY_PREFIX + conversationId, value, TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("写入取消 pending 失败, conversationId={}", conversationId, e);
        }
    }

    /**
     * 读取待确认状态；不存在或解析失败返回 null
     */
    public PendingOrder get(String conversationId) {
        try {
            String value = redisTemplate.opsForValue().get(KEY_PREFIX + conversationId);
            if (value == null || value.isBlank()) {
                return null;
            }
            return objectMapper.readValue(value, PendingOrder.class);
        } catch (Exception e) {
            log.warn("读取取消 pending 失败, conversationId={}", conversationId, e);
            return null;
        }
    }

    /**
     * 清除待确认状态
     */
    public void clear(String conversationId) {
        try {
            redisTemplate.delete(KEY_PREFIX + conversationId);
        } catch (Exception e) {
            log.warn("清除取消 pending 失败, conversationId={}", conversationId, e);
        }
    }

    /**
     * 判定用户对 pending 的回复类型
     *
     * <p>规则顺序（顺序即优先级）：反悔 → 提问 → 明确确认 → 其他。</p>
     *
     * @param message 用户本轮消息
     * @return 三态判定结果，永不返回 null
     */
    public Reply judge(String message) {
        if (message == null || message.isBlank()) {
            return Reply.OTHER;
        }
        String text = message.replaceAll("\\s+", "");

        // ① 反悔优先：「不要取消」必须优先于「确认」，否则含「取消」的否定句会被当成确认
        if (hasAny(text, REVERSE_WORDS)) {
            return Reply.REVERSE;
        }
        // ② 提问一律不执行：「取消要手续费吗」「确认取消吗」都不是确认
        if (text.contains("?") || text.contains("？") || text.endsWith("吗")) {
            return Reply.OTHER;
        }
        // ③ 明确确认：短句 + 命中确认词
        if (text.length() <= CONFIRM_MAX_LEN && hasAny(text, CONFIRM_WORDS)) {
            return Reply.CONFIRM;
        }
        return Reply.OTHER;
    }

    private static boolean hasAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
