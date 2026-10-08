package com.shop.rag.search;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RRF（Reciprocal Rank Fusion）融合参数配置属性
 * <p>
 * 权重默认 1.0 / 1.0（两路等权），与改造前行为完全一致——未配置时不改变任何排序结果，可随时回滚。
 * 调整权重用于评估对照（见 docs/rag-hybrid-tuning-design.md）与线上调优。
 * 配置示例：
 * <pre>
 * rag:
 *   rrf:
 *     k: 60
 *     weight-vector: 1.0
 *     weight-keyword: 1.5
 * </pre>
 * 非法配置（{@code k <= 0}、权重为负、两路权重同时为 0）不抛异常，回退默认值并打 WARN，
 * 避免一个笔误导致整个应用无法启动。
 *
 * @author shop
 */
@Data
@Slf4j
@Component
@ConfigurationProperties(prefix = "rag.rrf")
public class RrfProperties {

    /** RRF 平滑常数（业界常用 60）：越大越不敏感于名次差异 */
    private int k = 60;

    /** 向量路权重 */
    private double weightVector = 1.0;

    /** 关键词路权重 */
    private double weightKeyword = 1.0;

    /**
     * 校验并回退非法配置
     * <p>
     * 包可见以便单测在无 Spring 上下文时手动调用。
     */
    @PostConstruct
    void validate() {
        boolean fallback = false;
        if (k <= 0) {
            log.warn("rag.rrf.k={} 非法（须大于 0），回退默认值 60", k);
            k = 60;
            fallback = true;
        }
        if (weightVector < 0) {
            log.warn("rag.rrf.weight-vector={} 非法（不可为负），回退默认值 1.0", weightVector);
            weightVector = 1.0;
            fallback = true;
        }
        if (weightKeyword < 0) {
            log.warn("rag.rrf.weight-keyword={} 非法（不可为负），回退默认值 1.0", weightKeyword);
            weightKeyword = 1.0;
            fallback = true;
        }
        // 两路权重同时为 0 会让所有候选 RRF 分数恒为 0，排序退化为无序，视为非法配置
        if (weightVector == 0.0 && weightKeyword == 0.0) {
            log.warn("rag.rrf 两路权重同时为 0（融合排序将失去意义），回退默认值 1.0 / 1.0");
            weightVector = 1.0;
            weightKeyword = 1.0;
            fallback = true;
        }
        if (fallback) {
            log.warn("RRF 参数已回退默认值: k={}, weightVector={}, weightKeyword={}",
                    k, weightVector, weightKeyword);
        }
    }
}
