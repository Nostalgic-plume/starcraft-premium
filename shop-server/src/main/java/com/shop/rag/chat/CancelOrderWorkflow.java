package com.shop.rag.chat;

import com.shop.common.BusinessException;
import com.shop.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 取消订单确定性执行器（写操作）
 *
 * <p>
 * 取消订单已改为「两阶段」：
 * </p>
 * <ol>
 *   <li><b>提议阶段</b>：{@link ChatService} 让 agent 在只读工具 + {@link RequestCancelTool} 内完成
 *       查单与目标选择，工具只写 pending，不执行取消；</li>
 *   <li><b>执行阶段</b>：用户下一轮明确确认后，{@link CancelPendingService#judge} 判定为
 *       {@link CancelPendingService.Reply#CONFIRM}，由本类的 {@link #execute} 以确定性代码调用
 *       {@link OrderService#cancelOrderByUserId} 完成取消。</li>
 * </ol>
 *
 * <p>
 * <b>为什么写操作不留在 agent 手里：</b>LLM 可能被提示注入诱导、也可能在多个候选中选错订单；
 * 把执行权收归代码，配合归属校验、状态条件更新与用户确认，才能保证可审计、可回滚。
 * </p>
 *
 * @author shop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelOrderWorkflow implements IntentWorkflow {

    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("\\d{10,}");

    private final OrderService orderService;

    @Override
    public Intent intent() {
        return Intent.ORDER_CANCEL;
    }

    /**
     * 取消意图不再由本类消费：查单与提议在 agent loop 内完成，执行由 {@link #execute} 承担。
     * 保留实现仅为满足 {@link IntentWorkflow} 抽象。
     */
    @Override
    public boolean tryHandle(WorkflowContext ctx, Intent intent) {
        return false;
    }

    /**
     * 确定性执行取消（唯一写入口，不经 LLM）
     *
     * @param userId         当前用户（服务端注入）
     * @param conversationId 会话ID（仅用于审计）
     * @param orderId        待取消订单
     * @return 面向用户的结果文案
     */
    public String execute(Long userId, String conversationId, Long orderId) {
        try {
            orderService.cancelOrderByUserId(orderId, userId);
            log.info("[取消订单审计] userId={}, conversationId={}, orderId={}, result=SUCCESS",
                    userId, conversationId, orderId);
            return "订单 " + orderId + " 已成功取消，商品库存已释放。";
        } catch (BusinessException e) {
            log.info("[取消订单审计] userId={}, conversationId={}, orderId={}, result=FAIL, reason={}",
                    userId, conversationId, orderId, e.getMessage());
            return "取消失败：" + e.getMessage();
        }
    }

    /**
     * 从消息提取订单号（雪花长数字），取第一个 ≥10 位连续数字。
     * 供 {@link OrderQueryWorkflow} 等只读场景复用。
     */
    static Long extractOrderId(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = ORDER_NO_PATTERN.matcher(message);
        if (m.find()) {
            try {
                return Long.parseLong(m.group());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
