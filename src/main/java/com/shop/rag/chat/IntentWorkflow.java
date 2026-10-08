package com.shop.rag.chat;

/**
 * 意图子任务 workflow 抽象（自研轻量编排）
 * <p>
 * 外部 workflow：用确定性 Java 步骤控制"本意图能做什么、走到哪一步"，写操作只存在于
 * 受控 workflow 的确定性代码路径；agent（AiServices loop）只在需要语言生成/理解的场景
 * 以受限工具集运行——workflow 是铁轨，agent 是在铁轨内跑的车厢，不能越轨。
 * </p>
 *
 * @author shop
 */
public interface IntentWorkflow {

    /** 本 workflow 负责的意图 */
    Intent intent();

    /**
     * 尝试处理当前消息
     *
     * @param ctx    执行上下文（会话/用户/消息/SSE）
     * @param intent 意图识别结果
     * @return true = 本 workflow 消费了该消息（已产生回复并收尾）；false = 未消费，交由默认链路
     */
    boolean tryHandle(WorkflowContext ctx, Intent intent);
}
