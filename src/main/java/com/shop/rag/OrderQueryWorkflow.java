package com.shop.rag;

import com.shop.common.BusinessException;
import com.shop.constant.OrderConstants;
import com.shop.model.vo.OrderVO;
import com.shop.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单查询受控 workflow（只读）
 * <p>
 * 查询订单是<b>有限集合的数据访问</b>，本 workflow 优先用确定性 Java 步骤处理结构化查询
 * （按订单号查详情 / 按状态查列表 / 查全部），直接调 {@link OrderService} 只读方法并返回人话结果——
 * 不依赖 LLM 理解，快速、受控、低延迟。
 * </p>
 * <p>
 * 仅当消息无法被规则解析为结构化参数（如"我上周买的那件外套到哪了"这类模糊指代）时返回
 * {@code false}，交由 ChatService 的只读 agent 兜底——兜底 agent 也只持有只读 {@link OrderQueryTool}，
 * 写操作依旧不在任何 agent 手里。
 * </p>
 *
 * @author shop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderQueryWorkflow implements IntentWorkflow {

    private final OrderService orderService;

    private static final int LIST_LIMIT = 10;

    @Override
    public Intent intent() {
        return Intent.ORDER_QUERY;
    }

    @Override
    public boolean tryHandle(WorkflowContext ctx, Intent intent) {
        if (intent != Intent.ORDER_QUERY) {
            return false;
        }
        String msg = ctx.getUserMessage();
        Long userId = ctx.getUserId();

        // ① 结构化：消息含订单号 → 查详情（确定性只读 + 归属校验）
        Long orderId = CancelOrderWorkflow.extractOrderId(msg);
        if (orderId != null) {
            ctx.replyAndComplete(buildOrderDetailText(orderId, userId));
            return true;
        }

        // ② 结构化：消息含状态词 → 按状态查列表
        Integer status = extractStatus(msg);
        if (status != null) {
            ctx.replyAndComplete(buildOrderListText(
                    orderService.myOrdersByUserId(userId, status, LIST_LIMIT)));
            return true;
        }

        // ③ 结构化：查全部（"我的订单""都买了什么"等无状态词）
        if (isQueryAll(msg)) {
            ctx.replyAndComplete(buildOrderListText(
                    orderService.myOrdersByUserId(userId, null, LIST_LIMIT)));
            return true;
        }

        // ④ 无法规则解析（模糊指代长句）→ 交回只读 agent 兜底（只读安全）
        return false;
    }

    /** 按订单号查详情（确定性只读 + OrderService 归属校验） */
    private String buildOrderDetailText(Long orderId, Long userId) {
        OrderVO order;
        try {
            order = orderService.getOrderDetailByUserId(orderId, userId);
        } catch (BusinessException e) {
            return "该订单不存在或不属于您";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("订单 ").append(order.getOrderNo())
                .append("：状态【").append(order.getStatusText()).append("】\n");
        if (order.getItems() != null && !order.getItems().isEmpty()) {
            for (OrderVO.OrderItemVO item : order.getItems()) {
                String name = item.getProductName() != null ? item.getProductName() : item.getSkuName();
                sb.append("· ").append(name)
                        .append(" ×").append(item.getQuantity())
                        .append("（¥").append(item.getPrice()).append("）\n");
            }
        }
        sb.append("合计：¥").append(amountOf(order));
        if (order.getCreateTime() != null) {
            sb.append("｜下单：").append(order.getCreateTime().toLocalDate());
        }
        return sb.toString();
    }

    /** 订单列表人话化 */
    private String buildOrderListText(List<OrderVO> orders) {
        if (orders == null || orders.isEmpty()) {
            return "您目前还没有相关订单";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(orders.size()).append(" 个订单：\n");
        for (OrderVO order : orders) {
            sb.append("· 订单 ").append(order.getOrderNo())
                    .append("｜").append(order.getStatusText())
                    .append("｜").append(firstItemSummary(order))
                    .append("｜¥").append(amountOf(order)).append("\n");
        }
        return sb.toString();
    }

    private static String firstItemSummary(OrderVO order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return "";
        }
        OrderVO.OrderItemVO item = order.getItems().get(0);
        String name = item.getProductName() != null ? item.getProductName() : item.getSkuName();
        return (name != null ? name : "商品") + " ×" + item.getQuantity();
    }

    private static BigDecimal amountOf(OrderVO order) {
        return order.getActualAmount() != null ? order.getActualAmount() : order.getTotalAmount();
    }

    /** 状态词 → 订单状态码（OrderConstants）；未识别返回 null */
    static Integer extractStatus(String msg) {
        if (msg == null) {
            return null;
        }
        if (msg.contains("待付款") || msg.contains("待支付") || msg.contains("未付款")) {
            return OrderConstants.STATUS_PENDING_PAY;
        }
        if (msg.contains("待发货") || msg.contains("已支付")) {
            return OrderConstants.STATUS_PAID;
        }
        if (msg.contains("待收货") || msg.contains("已发货")) {
            return OrderConstants.STATUS_DELIVERED;
        }
        if (msg.contains("已完成") || msg.contains("已收货") || msg.contains("收到")) {
            return OrderConstants.STATUS_RECEIVED;
        }
        if (msg.contains("已取消")) {
            return OrderConstants.STATUS_CANCELLED;
        }
        return null;
    }

    /** 是否"查全部"的表述（无状态词、无订单号） */
    private static boolean isQueryAll(String msg) {
        if (msg == null) {
            return false;
        }
        return msg.contains("我的订单") || msg.contains("订单列表") || msg.contains("都买了什么")
                || msg.contains("全部订单") || msg.contains("有哪些订单") || msg.contains("买了什么");
    }
}
