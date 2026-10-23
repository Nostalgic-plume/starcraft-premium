package com.shop.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 检索参数（相关性门槛、候选池规模、topK 上限）
 * <p>
 * 这些值此前是 {@code RagSearchService} 内的类常量，2026-10-18 外置。原因：
 * <ul>
 *   <li>{@link #minRelevanceScore} 与 embedding 模型**强相关** —— 换模型（如 BGE-M3 → 其他）
 *       后向量分数分布会变，写死的 0.3 会静默失效（过滤过严则召回骤降，过松则噪声涌入），
 *       且没有任何告警；外置后可随模型一起调整。</li>
 *   <li>{@link #candidateMultiplier} 等候选池参数与 RRF 权重（{@code rag.rrf}）
 *       同属检索调优旋钮，之前一半外置一半硬编码，口径不一致。</li>
 * </ul>
 * <p>
 * 默认值与改造前的常量完全一致，不配也能保持行为不变。
 *
 * @author shop
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.search")
public class RagSearchProperties {

    /**
     * 向量路最低相似度门槛（低于此值不进入候选池）
     * <p>
     * ⚠️ 换 embedding 模型后必须重估：不同模型的余弦分数分布差异很大，
     * 沿用旧阈值会导致召回质量的静默劣化。
     */
    private double minRelevanceScore = 0.3;

    /** 候选池 = topK × 该倍数（再由 minQueryLimit 兜底下限） */
    private int candidateMultiplier = 5;

    /** 候选池下限：topK 很小时也要保证向量路有足够的融合候选 */
    private int minQueryLimit = 50;

    /** 候选池上限：防止超大 topK 放大 MySQL LIMIT 与 Qdrant maxResults */
    private int maxQueryLimit = 200;

    /** topK 上限：公开接口未校验参数，超大值会放大下游查询成本 */
    private int maxTopK = 100;

    /**
     * 是否启用向量路检索
     *
     * <p>默认 true（向量 + 关键词双路加权 RRF 融合）。置 false 时**完全跳过**向量化与
     * Qdrant 检索——连 TEI 的 embedding 请求都不发，避免未部署向量服务时每次查询都
     * 白等一次连接超时。
     *
     * <p>关闭后只走关键词路（ES，故障自动回退 MySQL FULLTEXT）；RRF 融合对单路为空有
     * 专门分支，因此无需改动融合逻辑，排序结果等价于单路排序。
     */
    private boolean vectorEnabled = true;
}
