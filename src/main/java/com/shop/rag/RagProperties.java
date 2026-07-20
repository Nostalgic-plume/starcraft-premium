package com.shop.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 配置属性 - Qdrant + Ollama
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
    /** 向量维度（Qwen3.5 默认 1024） */
    private int dimension = 1024;
}
