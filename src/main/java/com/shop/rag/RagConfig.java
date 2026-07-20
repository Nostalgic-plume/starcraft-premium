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
     * 启动时检查并创建 Qdrant 集合（LangChain4j QdrantEmbeddingStore 不会自动创建）
     */
    @PostConstruct
    public void initCollection() {
        try (QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder(ragProperties.getHost(), ragProperties.getPort(), false).build())) {

            boolean exists = client.collectionExistsAsync(ragProperties.getCollectionName()).get();
            if (!exists) {
                log.info("Qdrant 集合 '{}' 不存在，正在创建...", ragProperties.getCollectionName());
                client.createCollectionAsync(ragProperties.getCollectionName(),
                        VectorParams.newBuilder()
                                .setSize(ragProperties.getDimension())
                                .setDistance(Distance.Cosine)
                                .build()).get();
                log.info("Qdrant 集合 '{}' 创建成功", ragProperties.getCollectionName());
            } else {
                log.info("Qdrant 集合 '{}' 已存在", ragProperties.getCollectionName());
            }
        } catch (Exception e) {
            log.error("Qdrant 集合初始化失败: {}", e.getMessage());
        }
    }

    @Bean
    public OllamaEmbeddingModel ollamaEmbeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("bge-m3")
                .build();
    }

    @Bean
    public OllamaStreamingChatModel ollamaStreamingChatModel() {
        return OllamaStreamingChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("qwen3.5:0.8b")
                .temperature(0.7)
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
