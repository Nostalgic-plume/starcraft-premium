package com.shop.rag.chat;

/**
 * 客服对话意图枚举
 * <p>
 * 意图识别层把用户消息归类到有限意图，决定由哪个 workflow / 工具集处理。
 * 设计目标：按意图收窄暴露给 agent 的能力（最小权限）——写操作意图走受控 workflow，
 * 通用对话 agent 始终不持有写工具。
 * </p>
 *
 * @author shop
 */
public enum Intent {

    /** 取消订单（写操作）：走 CancelOrderWorkflow 确定性执行 + 跨轮用户确认 */
    ORDER_CANCEL("order_cancel", "取消订单"),
    /** 查询订单（只读）：agent 仅持有只读订单工具 */
    ORDER_QUERY("order_query", "查询订单"),
    /** 闲聊（问候/致谢/告别/情绪/身份/陪聊）：不检索商品、不推商品卡片，走纯对话 */
    CHITCHAT("chitchat", "闲聊"),
    /** 商品推荐 / RAG 问答：默认，agent 不持订单工具（纯 RAG 生成） */
    PRODUCT("product", "商品咨询");

    /** SSE route 事件用的稳定编码（前端据此显示状态条） */
    private final String code;
    /** 中文说明（前端状态条 / 日志） */
    private final String label;

    Intent(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
