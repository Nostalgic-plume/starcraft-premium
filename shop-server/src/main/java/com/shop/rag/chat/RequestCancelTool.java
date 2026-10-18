package com.shop.rag.chat;

import com.shop.common.BusinessException;
import com.shop.constant.OrderConstants;
import com.shop.model.vo.OrderVO;
import com.shop.service.OrderService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

/**
 * 取消订单「请求」工具（半写，Function Calling）
 *
 * <p>
 * <b>只发起请求，不执行取消。</b>工具内部只做三件事：解析订单号 → 归属/状态校验 → 写 pending。
 * 真正执行取消由用户在下一轮明确确认后、由 {@link CancelOrderWorkflow} 的确定性代码完成——
 * LLM 手里永远没有执行写操作的能力，避免被诱导或幻觉取消错单。
 * </p>
 *
 * <p>
 * 身份绑定：userId 与 conversationId 均由构造器传入（每请求 new），
 * <b>绝不接收 LLM 生成的参数</b>；归属校验在 {@link OrderService} 层强制。
 * </p>
 *
 * @author shop
 */
@Slf4j
public class RequestCancelTool {

    private final Long userId;
    private final String conversationId;
    private final OrderService orderService;
    private final CancelPendingService cancelPendingService;

    public RequestCancelTool(Long userId, String conversationId,
                             OrderService orderService, CancelPendingService cancelPendingService) {
        this.userId = userId;
        this.conversationId = conversationId;
        this.orderService = orderService;
        this.cancelPendingService = cancelPendingService;
    }

    @Tool("发起取消订单请求（只发起请求，不会真正取消）。当用户明确要取消某笔待支付订单时调用；"
            + "调用后系统会把订单展示给用户并等待下一轮明确确认。"
            + "用户只是在询问取消规则、退款政策时需要先回答，不要调用本工具。")
    public String requestCancelOrder(@P(name = "orderId",
            description = "订单编号，用户下单后收到的长数字订单号") String orderId) {
        Long id = parseOrderId(orderId);
        if (id == null) {
            return "订单编号无效，请确认后重试";
        }
        try {
            OrderVO order = orderService.getOrderDetailByUserId(id, userId);
            if (!Integer.valueOf(OrderConstants.STATUS_PENDING_PAY).equals(order.getStatus())) {
                return "订单 " + order.getOrderNo() + " 当前状态【" + order.getStatusText()
                        + "】，仅待支付订单可取消";
            }
            cancelPendingService.write(conversationId, userId, id);
            String createTime = order.getCreateTime() != null
                    ? "，下单时间 " + order.getCreateTime().format(OrderQueryTool.DATE_TIME_FORMATTER)
                    : "";
            return "您要取消订单 " + order.getOrderNo() + "（¥" + amountOf(order) + createTime
                    + "）对吗？取消后库存会自动释放且不可恢复，请回复「确认取消」完成操作。";
        } catch (BusinessException e) {
            // 订单不存在 / 非本人订单统一文案，防止订单号枚举探测
            return "该订单不存在或不属于您";
        } catch (Exception e) {
            log.warn("requestCancelOrder 工具执行异常, userId={}", userId, e);
            return "订单服务暂时不可用，请稍后再试";
        }
    }

    /**
     * 订单号字符串 → Long；非法或超范围返回 null。
     * 参数用 String 而非 Long：AiServices 对超范围 Long 参数会在工具方法执行前抛异常，导致整轮 onError。
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

    private static String amountOf(OrderVO order) {
        return String.valueOf(order.getActualAmount() != null ? order.getActualAmount() : order.getTotalAmount());
    }
}
