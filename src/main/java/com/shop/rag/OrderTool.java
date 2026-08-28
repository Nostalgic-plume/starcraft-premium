package com.shop.rag;

import com.shop.common.BusinessException;
import com.shop.constant.OrderConstants;
import com.shop.model.vo.OrderVO;
import com.shop.service.OrderService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * AI 客服订单查询工具（Function Calling）
 * <p>
 * 供 LangChain4j AiServices 注册的工具类。设计约束：
 * <ul>
 *   <li><b>身份显式绑定</b>：userId 由构造器传入（每请求 new 一个实例），绝不接收 LLM 生成的 userId，
 *       归属校验在 {@link OrderService} 层强制（安全边界），本类只做「调用 Service + 组装人话」。</li>
 *   <li><b>返回人话化</b>：返回给 LLM 的是可直接引用的中文文本，不返回 DO/JSON（省 token、防截断）。</li>
 *   <li><b>异常兜底</b>：任何异常 catch 后返回可读降级文案，不抛未捕获异常（避免断 SSE）。</li>
 *   <li><b>只读</b>：V1 仅查询，不做写操作。</li>
 * </ul>
 *
 * @author shop
 */
@Slf4j
public class OrderTool {

    private final Long userId;
    private final OrderService orderService;

    public OrderTool(Long userId, OrderService orderService) {
        this.userId = userId;
        this.orderService = orderService;
    }

    /**
     * 查询当前登录用户自己的订单列表，可按状态过滤
     *
     * @param status 订单状态（中文），可选：待付款/待发货/待收货/已完成/已取消；省略则查全部
     * @return 人话化的订单列表文本
     */
    @Tool("查询当前登录用户自己的订单列表，可按状态过滤。当用户询问自己的订单情况（如\"我的订单\"\"有几个待发货的\"\"都买了什么\"）时调用。")
    public String getMyOrders(@P(name = "status",
            description = "订单状态，可选值：待付款/待发货/待收货/已完成/已取消；省略则查全部",
            required = false) String status) {
        try {
            Integer statusCode = mapStatus(status);
            List<OrderVO> orders = orderService.myOrdersByUserId(userId, statusCode, 10);
            if (orders.isEmpty()) {
                return status != null && !status.isBlank()
                        ? "您目前还没有【" + status + "】的订单"
                        : "您目前还没有订单";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("您有 ").append(orders.size()).append(" 个订单：\n");
            for (int i = 0; i < orders.size(); i++) {
                OrderVO order = orders.get(i);
                sb.append(i + 1).append(". 订单号 ").append(order.getOrderNo())
                        .append("｜").append(order.getStatusText())
                        .append("｜").append(firstItemSummary(order))
                        .append("｜¥").append(order.getTotalAmount());
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
     * 取消当前登录用户的待支付订单（写操作）
     *
     * @param orderId 订单编号（用户下单后收到的订单号）
     * @return 人话化的取消结果文本
     */
    @Tool("取消当前登录用户自己的待支付订单。仅当用户明确要求取消某个订单（如\"取消订单\"\"把订单退了\"\"不要这个订单了\"）时调用，取消后库存自动回滚。")
    public String cancelOrder(@P(name = "orderId", description = "待取消的订单编号，用户下单后收到的订单号") String orderId) {
        Long orderIdLong = parseOrderId(orderId);
        if (orderIdLong == null) {
            return "订单编号无效，请确认后重试";
        }
        try {
            orderService.cancelOrderByUserId(orderIdLong, userId);
            return "订单 " + orderIdLong + " 已成功取消";
        } catch (BusinessException e) {
            String msg = e.getMessage();
            // 归属/存在校验失败统一返回固定文案（写操作安全边界，防订单号枚举探测）
            if ("订单不存在".equals(msg) || "无权操作该订单".equals(msg)) {
                return "该订单不存在或不属于您";
            }
            // 状态不允许等 → 返回具体文案（幂等：重复取消友好提示）
            return msg;
        } catch (Exception e) {
            log.warn("cancelOrder 工具执行异常, orderId={}, userId={}", orderId, userId, e);
            return "订单服务暂时不可用，请稍后再试";
        }
    }

    /**
     * 订单号字符串 → Long；非法/超范围（如超出 Long.MAX_VALUE 的 9999999999999999999）返回 null
     * 工具参数用 String（而非 Long）的原因：AiServices 的 DefaultToolExecutor 对超范围 Long 参数
     * 在工具方法执行前抛 IllegalArgumentException（不走工具内 catch），会导致整轮对话 onError。
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
