package com.shop.rag.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.model.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 候选订单上下文存储（跨轮指代用）
 *
 * <p>
 * agent loop 的工具结果只在当轮上下文可见，下一轮记忆里不保留工具往返消息。
 * 用户说「就那笔」「第一笔」时，若没有候选上下文，LLM 只能靠聊天历史猜订单号——
 * 一旦记错就会取消错单。这里把最近一次查询到的候选订单按会话暂存（TTL 10 分钟），
 * 下一轮注入 system prompt，限定 LLM 只能从列表中选择。
 * </p>
 *
 * @author shop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCandidateStore {

    private static final String KEY_PREFIX = "chat:candidate:orders:";
    private static final long TTL_MINUTES = 10;
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /** 轻量候选视图：只保留 LLM 判断所需的字段，不落商品明细与 PII */
    public record Candidate(String orderNo, String statusText, String amount, String createTime) {
    }

    /**
     * 保存本轮查询到的候选订单（覆盖写）
     */
    public void save(String conversationId, List<OrderVO> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        try {
            List<Candidate> candidates = new ArrayList<>();
            for (OrderVO order : orders) {
                candidates.add(new Candidate(
                        order.getOrderNo(),
                        order.getStatusText(),
                        String.valueOf(order.getActualAmount() != null
                                ? order.getActualAmount() : order.getTotalAmount()),
                        order.getCreateTime() != null
                                ? order.getCreateTime().format(DATE_TIME_FORMATTER) : ""));
            }
            String json = objectMapper.writeValueAsString(candidates);
            redisTemplate.opsForValue().set(KEY_PREFIX + conversationId, json, TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("保存候选订单失败, conversationId={}", conversationId, e);
        }
    }

    /**
     * 读取候选订单；不存在或解析失败返回空列表
     */
    public List<Candidate> get(String conversationId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + conversationId);
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, new TypeReference<List<Candidate>>() {
            });
        } catch (Exception e) {
            log.warn("读取候选订单失败, conversationId={}", conversationId, e);
            return List.of();
        }
    }
}
