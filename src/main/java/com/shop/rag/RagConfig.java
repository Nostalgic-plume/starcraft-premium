package com.shop.rag;

import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

/**
 * LangChain4j + Qdrant + Ollama 配置
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RagProperties.class)
@RequiredArgsConstructor
public class RagConfig {

    private final RagProperties ragProperties;

    /**
     * 启动时重建 Qdrant 集合，确保向量维度一致（清除旧脏数据）
     */
    @PostConstruct
    public void initCollection() {
        try (QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder(ragProperties.getHost(), ragProperties.getPort(), false).build())) {

            log.info("正在重建 Qdrant 集合 '{}' ...", ragProperties.getCollectionName());
            client.recreateCollectionAsync(ragProperties.getCollectionName(),
                    VectorParams.newBuilder()
                            .setSize(ragProperties.getDimension())
                            .setDistance(Distance.Cosine)
                            .build()).get();
            log.info("Qdrant 集合 '{}' 重建完成（维度: {}）", ragProperties.getCollectionName(), ragProperties.getDimension());
        } catch (Exception e) {
            log.error("Qdrant 集合重建失败，请确认 Qdrant gRPC 端口（6334）是否可访问: {}", e.getMessage());
        }
    }

    @Bean
    public OllamaEmbeddingModel ollamaEmbeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("bge-m3")
                .timeout(Duration.ofMinutes(2))
                .build();
    }

    @Bean
    public OllamaStreamingChatModel ollamaStreamingChatModel() {
        return OllamaStreamingChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("qwen3.5:0.8b")
                .temperature(0.7)
                .timeout(Duration.ofMinutes(3))
                .build();
    }

    @Bean
    public QdrantEmbeddingStore qdrantEmbeddingStore() {
        return QdrantEmbeddingStore.builder()
                .host(ragProperties.getHost())
                .port(ragProperties.getPort())
                .collectionName(ragProperties.getCollectionName())
                .build();
    }
}
