package com.shop.rag;

import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

/**
 * LangChain4j + Qdrant + DashScope 配置
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RagProperties.class)
@RequiredArgsConstructor
public class RagConfig {

    private final RagProperties ragProperties;
    private final QdrantCollectionHelper qdrantCollectionHelper;

    /**
     * 启动时确保 Qdrant 集合已存在，不存在则创建
     */
    @PostConstruct
    public void initCollection() {
        qdrantCollectionHelper.ensureCollection();
    }

    @Bean
    public OpenAiEmbeddingModel openAiEmbeddingModel(
            @Value("${rag.llm.api-key}") String apiKey,
            @Value("${rag.llm.embedding-model:text-embedding-v1}") String modelName) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofMinutes(2))
                .build();
    }

    @Bean
    public OpenAiStreamingChatModel openAiStreamingChatModel(
            @Value("${rag.llm.api-key}") String apiKey,
            @Value("${rag.llm.chat-model:qwen-plus}") String modelName) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .apiKey(apiKey)
                .modelName(modelName)
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
