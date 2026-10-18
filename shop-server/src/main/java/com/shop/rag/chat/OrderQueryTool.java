package com.shop.rag.chat;

import com.shop.common.BusinessException;
import com.shop.constant.OrderConstants;
import com.shop.model.vo.OrderVO;
import com.shop.service.OrderService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 订单查询工具（只读，Function Calling）
 * <p>
 * 供通用对话 agent（AiServices）注册的<b>只读</b>工具，仅含查询能力，<b>不含任何写操作</b>。
 * 写操作（取消订单）不暴露给任何 agent，只能进入受控的 {@code CancelOrderWorkflow} 确定性执行——
 * 这是「防止 agent 乱操作」的架构落点：LLM 手里永远没有写工具，无法在普通对话里被诱导触发取消。
 * </p>
 * <ul>
 *   <li><b>身份显式绑定</b>：userId 由构造器传入（每请求 new），绝不接收 LLM 生成的 userId；
 *       归属校验在 {@link OrderService} 层强制（安全边界）。</li>
 *   <li><b>返回人话化</b>：返回给 LLM 的是可直接引用的中文文本，不返回 DO/JSON。</li>
 *   <li><b>异常兜底</b>：任何异常 catch 后返回可读降级文案，不抛未捕获异常（避免断 SSE）。</li>
 * </ul>
 *
 * @author shop
 */
@Slf4j
public class OrderQueryTool {

    static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Long userId;
    private final String conversationId;
    private final OrderService orderService;
    private final OrderCandidateStore orderCandidateStore;

    public OrderQueryTool(Long userId, String conversationId,
                          OrderService orderService, OrderCandidateStore orderCandidateStore) {
        this.userId = userId;
        this.conversationId = conversationId;
        this.orderService = orderService;
        this.orderCandidateStore = orderCandidateStore;
    }

    /**
     * 查询当前登录用户自己的订单列表，可按状态过滤
     *
     * @param status 订单状态（中文），可选：待付款/待发货/待收货/已完成/已取消；省略则查全部
     * @return 人话化的订单列表文本
     */
    @Tool("查询当前登录用户自己的订单列表，可按状态和时间范围过滤。当用户询问自己的订单情况（如\"我的订单\"\"有几个待发货的\"\"昨天的订单\"\"都买了什么\"）时调用。")
    public String getMyOrders(@P(name = "status",
            description = "订单状态，可选值：待付款/待发货/待收货/已完成/已取消；省略则查全部",
            required = false) String status,
            @P(name = "relativeDay",
            description = "相对时间范围，可选值：TODAY（今天）/YESTERDAY（昨天）/LAST_3_DAYS（最近三天）/LAST_7_DAYS（最近七天）；省略则不限时间",
            required = false) String relativeDay) {
        try {
            Integer statusCode = mapStatus(status);
            LocalDateTime[] range = mapTimeRange(relativeDay);
            List<OrderVO> orders = orderService.myOrdersByUserIdAndTime(
                    userId, statusCode, range[0], range[1], 10);
            // 暂存候选，供下一轮「就那笔」「第一笔」这类跨轮指代使用
            orderCandidateStore.save(conversationId, orders);
            if (orders.isEmpty()) {
                String timeText = relativeDay == null || relativeDay.isBlank() ? "" : "在" + relativeDay + "范围内";
                return status != null && !status.isBlank()
                        ? "您" + timeText + "没有【" + status + "】的订单"
                        : "您" + timeText + "没有订单";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("您有 ").append(orders.size()).append(" 个订单：\n");
            for (int i = 0; i < orders.size(); i++) {
                OrderVO order = orders.get(i);
                sb.append(i + 1).append(". 订单号 ").append(order.getOrderNo())
                        .append("｜").append(order.getStatusText())
                        .append("｜").append(firstItemSummary(order))
                        .append("｜¥").append(order.getTotalAmount());
                if (order.getCreateTime() != null) {
                    sb.append("｜下单 ").append(order.getCreateTime().format(DATE_TIME_FORMATTER));
                }
                if (i < orders.size() - 1) {
                    sb.append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("getMyOrders 工具执行异常, userId={}", userId, e);
            return "订单服务暂时不可用，请稍后再试";
        }
    }

    /**
     * 相对时间描述 → [start, end) 区间；无法识别返回 [null, null]（不限时间）。
     * 日期换算由代码完成，不交给 LLM——跨月、时区都容易算错。
     */
    private static LocalDateTime[] mapTimeRange(String relativeDay) {
        if (relativeDay == null || relativeDay.isBlank()) {
            return new LocalDateTime[]{null, null};
        }
        LocalDate today = LocalDate.now();
        return switch (relativeDay.trim().toUpperCase(Locale.ROOT)) {
            case "TODAY" -> new LocalDateTime[]{today.atStartOfDay(), today.plusDays(1).atStartOfDay()};
            case "YESTERDAY" -> new LocalDateTime[]{today.minusDays(1).atStartOfDay(), today.atStartOfDay()};
            case "LAST_3_DAYS" -> new LocalDateTime[]{today.minusDays(2).atStartOfDay(), today.plusDays(1).atStartOfDay()};
            case "LAST_7_DAYS" -> new LocalDateTime[]{today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay()};
            default -> new LocalDateTime[]{null, null};
        };
    }

    /**
     * 查询当前登录用户某个订单的详细状态
     *
     * @param orderId 订单编号（用户下单后收到的订单号）
     * @return 人话化的订单详情文本
     */
    @Tool("查询当前登录用户某个订单的详细状态。当用户询问某个具体订单的进展（到哪了/发货没/多少钱/下单时间）并提到订单编号时调用。")
    public String getOrderStatus(@P(name = "orderId", description = "订单编号，用户下单后收到的订单号") String orderId) {
        Long orderIdLong = parseOrderId(orderId);
        if (orderIdLong == null) {
            return "订单编号无效，请确认后重试";
        }
        try {
            OrderVO order = orderService.getOrderDetailByUserId(orderIdLong, userId);
            StringBuilder sb = new StringBuilder();
            sb.append("订单 ").append(order.getOrderNo())
                    .append("：状态【").append(order.getStatusText()).append("】\n");
            if (order.getItems() != null && !order.getItems().isEmpty()) {
                for (OrderVO.OrderItemVO item : order.getItems()) {
                    String name = item.getProductName() != null ? item.getProductName() : item.getSkuName();
                    sb.append("商品：").append(name)
                            .append(" ×").append(item.getQuantity())
                            .append("（¥").append(item.getPrice()).append("）\n");
                }
            }
            sb.append("合计：¥").append(order.getActualAmount() != null ? order.getActualAmount() : order.getTotalAmount());
            if (order.getCreateTime() != null) {
                sb.append("｜下单时间：")
                        .append(order.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            }
            return sb.toString();
        } catch (BusinessException e) {
            // 订单不存在或非本人订单统一返回固定文案（安全边界，防订单号枚举探测）
            return "该订单不存在或不属于您";
        } catch (Exception e) {
            log.warn("getOrderStatus 工具执行异常, orderId={}, userId={}", orderId, userId, e);
            return "订单服务暂时不可用，请稍后再试";
        }
    }

    /**
     * 订单号字符串 → Long；非法/超范围返回 null（工具参数用 String 而非 Long，
     * 规避 AiServices 对超范围 Long 参数在工具方法执行前抛异常导致整轮 onError）
     */
    private static Long parseOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(orderId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 中文状态 → 订单状态码（OrderConstants），无法识别返回 null（查全部） */
    private static Integer mapStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return switch (status.trim()) {
            case "待付款" -> OrderConstants.STATUS_PENDING_PAY;
            case "待发货" -> OrderConstants.STATUS_PAID;
            case "待收货" -> OrderConstants.STATUS_DELIVERED;
            case "已完成" -> OrderConstants.STATUS_RECEIVED;
            case "已取消" -> OrderConstants.STATUS_CANCELLED;
            default -> null;
        };
    }

    /** 取订单第一个商品的摘要（名称 ×数量） */
    private static String firstItemSummary(OrderVO order) {
        if (order.getItems() == null || order.getItems().isEmpty()) {
            return "";
        }
        OrderVO.OrderItemVO item = order.getItems().get(0);
        String name = item.getProductName() != null ? item.getProductName() : item.getSkuName();
        return (name != null ? name : "商品") + " ×" + item.getQuantity();
    }
}
