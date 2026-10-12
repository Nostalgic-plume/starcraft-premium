package com.shop.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 配置属性 - Qdrant
 *
 * @author shop
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.qdrant")
public class RagProperties {
    /** Qdrant gRPC 主机 */
    private String host = "localhost";
    /** Qdrant gRPC 端口 */
    private int port = 6334;
    /** 集合名称 */
    private String collectionName = "products";
    /**
     * 向量维度（BGE-M3 为 1024）
     * <p>
     * 这是全链路维度的**唯一事实来源**：建集合（QdrantCollectionHelper）与查询向量自检
     * （RagSearchService）都读此值，避免出现「配置改了、自检常量没改」导致的静默降级。
     */
    private int dimension = 1024;
}
