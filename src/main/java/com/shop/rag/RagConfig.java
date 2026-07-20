package com.shop.rag;

import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j + Qdrant + Ollama 配置
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
@RequiredArgsConstructor
public class RagConfig {

    private final RagProperties ragProperties;

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
