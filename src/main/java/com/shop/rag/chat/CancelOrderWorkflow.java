package com.shop.rag.chat;

import com.shop.common.BusinessException;
import com.shop.constant.OrderConstants;
import com.shop.model.vo.OrderVO;
import com.shop.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 取消订单受控 workflow（写操作）
 * <p>
 * <b>安全核心</b>：取消订单是写操作，<b>绝不暴露给任何 agent 作为工具</b>。本 workflow 用确定性
 * Java 步骤完成：定位订单 → 归属/状态校验（OrderService，只读）→ 跨轮用户二次确认（Redis pending）
 * → 用户确认后由代码直接调 {@code OrderService.cancelOrderByUserId} 执行（不经 LLM）。
 * </p>
 * <p>
 * 两轮状态机：
 * <ul>
 *   <li>轮 1（意图=ORDER_CANCEL）：定位目标订单 → 校验 → 写 pending(orderId) → 返回确认话术</li>
 *   <li>轮 2（pending 存在 + 用户确认）：确定性执行取消 → 清 pending → 返回结果</li>
 * </ul>
 * 防误触：只有 pending 存在且用户明确确认（或仍表达取消）才执行；反悔词 / 转话题清 pending 落回默认。
 * </p>
 *
 * @author shop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelOrderWorkflow implements IntentWorkflow {

    private final OrderService orderService;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String PENDING_KEY_PREFIX = "chat:pending:cancel:";
    private static final long PENDING_TTL_MINUTES = 5;
    /** 订单号：雪花 ID 为长数字，取 ≥10 位连续数字，避免误取普通小数字 */
    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("\\d{10,}");

    @Override
    public Intent intent() {
        return Intent.ORDER_CANCEL;
    }

    @Override
    public boolean tryHandle(WorkflowContext ctx, Intent intent) {
        try {
            return handleInternal(ctx, intent);
        } catch (Exception e) {
            // 防子任务拖垮主流程：workflow 异常隔离，清 pending + 降级文案，不外抛
            log.error("取消订单 workflow 异常, userId={}", ctx.getUserId(), e);
            clearPending(ctx.getConversationId());
            ctx.replyAndComplete("取消失败，请稍后重试，或到「我的订单」页面操作");
            return true;
        }
    }

    private boolean handleInternal(WorkflowContext ctx, Intent intent) {
        String conversationId = ctx.getConversationId();
        String pendingOrderId = getPendingOrderId(conversationId);

        // 先处理 pending（上轮请求过取消的跨轮确认）
        if (pendingOrderId != null) {
            String msg = ctx.getUserMessage();
            // 反悔优先：用户说不取消了 → 清 pending，交回默认对话
            if (hasAny(msg, "不取消", "别取消", "不退了", "先不", "算了", "不用了", "不要取消了")) {
                clearPending(conversationId);
                return false;
            }
            if (intent == Intent.ORDER_CANCEL || isConfirmation(msg)) {
                doCancel(ctx, Long.parseLong(pendingOrderId));
                return true;
            }
            // 用户转话题 → 清 pending，走默认链路
            clearPending(conversationId);
            return false;
        }

        // 无 pending：仅当意图为取消才由本 workflow 处理
        if (intent != Intent.ORDER_CANCEL) {
            return false;
        }

        // 定位目标订单：优先消息内订单号
        Long orderId = extractOrderId(ctx.getUserMessage());
        if (orderId == null) {
            // 无订单号 → 列出最近待支付订单引导选择（确定性只读）
            String guide = buildOrderGuide(ctx);
            ctx.replyAndComplete(guide);
            return true;
        }

        // 确定性只读校验：归属 + 状态（OrderService 强制，订单号不存在/非本人统一文案）
        OrderVO order;
        try {
            order = orderService.getOrderDetailByUserId(orderId, ctx.getUserId());
        } catch (BusinessException e) {
            ctx.replyAndComplete("该订单不存在或不属于您，无法取消");
            return true;
        }
        if (!Integer.valueOf(OrderConstants.STATUS_PENDING_PAY).equals(order.getStatus())) {
            ctx.replyAndComplete("订单 " + orderId + " 当前状态【" + order.getStatusText()
                    + "】，仅待支付订单可取消");
            return true;
        }

        writePending(conversationId, orderId);
        ctx.replyAndComplete("您要取消订单 " + orderId + "（¥" + displayAmount(order)
                + "）对吗？取消后库存会自动释放且不可恢复，请回复「确认取消」完成操作。");
        return true;
    }

    /** 确定性执行取消（写操作，不经 agent），清 pending 后返回结果 */
    private void doCancel(WorkflowContext ctx, Long orderId) {
        try {
            orderService.cancelOrderByUserId(orderId, ctx.getUserId());
            clearPending(ctx.getConversationId());
            log.info("客服 workflow 取消订单成功, orderId={}, userId={}", orderId, ctx.getUserId());
            ctx.replyAndComplete("订单 " + orderId + " 已成功取消，商品库存已释放。");
        } catch (BusinessException e) {
            clearPending(ctx.getConversationId());
            ctx.replyAndComplete("取消失败：" + e.getMessage());
        }
    }

    /** 无订单号时，列出最近待支付订单引导用户提供订单号（确定性只读，不写 pending） */
    private String buildOrderGuide(WorkflowContext ctx) {
        try {
            List<OrderVO> pendingOrders =
                    orderService.myOrdersByUserId(ctx.getUserId(), OrderConstants.STATUS_PENDING_PAY, 5);
            if (pendingOrders.isEmpty()) {
                return "您目前没有待支付的订单，无需取消。如果是已支付/发货的订单，暂不支持在线取消哦～";
            }
            StringBuilder sb = new StringBuilder("您有以下待支付订单，请告诉我要取消哪一笔（发订单号即可）：\n");
            for (OrderVO o : pendingOrders) {
                sb.append("· 订单 ").append(o.getOrderNo())
                        .append("｜").append(o.getStatusText())
                        .append("｜¥").append(displayAmount(o)).append("\n");
            }
            sb.append("回复「取消订单 + 订单号」即可。");
            return sb.toString();
        } catch (Exception e) {
            log.warn("查询待支付订单引导失败, userId={}", ctx.getUserId(), e);
            return "为了您的账户安全，取消订单需要提供订单号，请把要取消的订单号发给我。";
        }
    }

    // ==================== Redis pending ====================

    private String pendingKey(String conversationId) {
        return PENDING_KEY_PREFIX + conversationId;
    }

    private String getPendingOrderId(String conversationId) {
        return redisTemplate.opsForValue().get(pendingKey(conversationId));
    }

    private void writePending(String conversationId, Long orderId) {
        redisTemplate.opsForValue().set(
                pendingKey(conversationId), String.valueOf(orderId),
                PENDING_TTL_MINUTES, TimeUnit.MINUTES);
    }

    private void clearPending(String conversationId) {
        redisTemplate.delete(pendingKey(conversationId));
    }

    // ==================== 文本判定 / 工具 ====================

    /** pending 存在时的确认词判定：短消息且含确认词（反悔词已在调用前排除） */
    private static boolean isConfirmation(String msg) {
        if (msg == null) {
            return false;
        }
        String t = msg.replaceAll("\\s+", "");
        if (t.length() > 12) {
            return false; // 长消息多为新请求，防误触发取消
        }
        return hasAny(t, "确认", "确定", "取消吧", "是的", "是", "好", "嗯", "对", "行");
    }

    private static boolean hasAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** 从消息提取订单号（雪花长数字），取第一个 ≥10 位连续数字 */
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

    private static String displayAmount(OrderVO order) {
        return String.valueOf(order.getActualAmount() != null ? order.getActualAmount() : order.getTotalAmount());
    }
}
