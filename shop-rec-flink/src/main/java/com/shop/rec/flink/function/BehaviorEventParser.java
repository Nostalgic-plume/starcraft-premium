package com.shop.rec.flink.function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.rec.flink.model.BehaviorEvent;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.metrics.Counter;

/**
 * 行为事件报文解析器
 *
 * <p>
 * 将 Kafka 中的 JSON 报文解析为 {@link BehaviorEvent}。反序列化失败或字段缺失的事件返回 null，
 * 由下游通过 side output 或过滤剔除——本类不抛异常，避免单条脏数据中断整个作业
 * （对应详细设计 3.2 异常分支「报文非法：跳过该条并计数，不中断作业」）。
 *
 * @author shop
 */
public class BehaviorEventParser extends RichMapFunction<String, BehaviorEvent> {

    private static final long serialVersionUID = 1L;

    /** ObjectMapper 线程安全，声明为 transient 避免参与序列化，在 open 中重建 */
    private transient ObjectMapper objectMapper;

    private transient Counter parseFailedCounter;

    @Override
    public void open(Configuration parameters) {
        objectMapper = new ObjectMapper();
        parseFailedCounter = getRuntimeContext().getMetricGroup().counter("parseFailed");
    }

    /**
     * 解析单条报文
     *
     * @param raw 原始 JSON 报文
     * @return 解析成功返回事件对象；失败返回 null
     */
    @Override
    public BehaviorEvent map(String raw) {
        if (raw == null || raw.isEmpty()) {
            parseFailedCounter.inc();
            return null;
        }
        try {
            BehaviorEvent event = objectMapper.readValue(raw, BehaviorEvent.class);
            if (!event.isValid()) {
                parseFailedCounter.inc();
                return null;
            }
            return event;
        } catch (Exception e) {
            parseFailedCounter.inc();
            return null;
        }
    }
}
