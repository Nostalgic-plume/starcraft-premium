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
 *
 * @author shop
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

    /**
     * 注册 DashScope 向量化模型 Bean（OpenAI 兼容模式，text-embedding-v1）
     * <p>
     * 用于把商品文本与用户 query 转成 1536 维向量，供 Qdrant 语义检索。
     *
     * @param apiKey    DashScope API Key（${rag.llm.api-key}）
     * @param modelName 向量化模型名，默认 text-embedding-v1
     * @return 配置了 DashScope 兼容端点与 2 分钟超时的嵌入模型实例
     */
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

    /**
     * 注册流式对话模型 Bean（OpenAI 兼容模式，DeepSeek deepseek-chat）
     * <p>
     * 用于智能客服 SSE 流式输出 + AiServices 工具链 agent loop 与 Query 改写。
     * 2026-08-28 从 DashScope qwen-plus 切换至 DeepSeek。注意：工具链死循环根因不是模型，
     * 而是 MySqlChatMemoryStore（AiServices 每轮从 store 重读 memory、工具往返消息丢失），
     * 已通过 store 活跃期缓存修复（见 MySqlChatMemoryStore 类注释）。
     *
     * @param apiKey    对话模型 API Key（${rag.llm.chat-api-key}，未配则回退 rag.llm.api-key）
     * @param modelName 对话模型名，默认 deepseek-chat
     * @return 配置了 DeepSeek 端点、温度 0.7、3 分钟超时的流式模型实例
     */
    @Bean
    public OpenAiStreamingChatModel openAiStreamingChatModel(
            @Value("${rag.llm.chat-api-key:${rag.llm.api-key}}") String apiKey,
            @Value("${rag.llm.chat-model:deepseek-chat}") String modelName) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.7)
                .timeout(Duration.ofMinutes(3))
                // 流式 tool-call ID：实测 DeepSeek 只在第一个 chunk 发完整 id、后续空（符合 OpenAI 标准），
                // 默认 accumulateToolCallId=true 累积正确。⚠️ 误设 false 会把 id 覆盖成空 → 工具回填不匹配死循环。
                .accumulateToolCallId(true)
                .build();
    }

    /**
     * 注册 Qdrant 向量存储 Bean（gRPC 连接）
     *
     * @return 指向 rag.qdrant 配置（host/port/collectionName）的向量存储实例
     */
    @Bean
    public QdrantEmbeddingStore qdrantEmbeddingStore() {
        return QdrantEmbeddingStore.builder()
                .host(ragProperties.getHost())
                .port(ragProperties.getPort())
                .collectionName(ragProperties.getCollectionName())
                .build();
    }
}
