package com.shop.rag;

import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

/**
 * LangChain4j + Qdrant + 模型端点配置
 * <p>
 * 对话与向量化端点一律外置到 {@link RagLlmProperties}（{@code rag.llm.*}），
 * 便于在 DashScope 停用后切换到本地 TEI / 异构备用供应商，并做到「改配置即回滚」。
 *
 * @author shop
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RagProperties.class)
@RequiredArgsConstructor
public class RagConfig {

    private final RagProperties ragProperties;
    private final RagLlmProperties ragLlmProperties;
    private final QdrantCollectionHelper qdrantCollectionHelper;

    /**
     * 启动时确保 Qdrant 集合已存在，不存在则创建
     */
    @PostConstruct
    public void initCollection() {
        qdrantCollectionHelper.ensureCollection();
    }

    /**
     * 注册向量化模型 Bean（OpenAI 兼容模式，端点外置）
     * <p>
     * 默认指向本地 TEI 部署的 BGE-M3（1024 维），维度须与 {@code rag.qdrant.dimension} 保持一致。
     * 端点 / 模型名 / Key 均由 {@code rag.llm.embedding-*} 提供，切换与回滚都只改配置。
     *
     * @return 配置了外置端点与 2 分钟超时的嵌入模型实例
     */
    @Bean
    public OpenAiEmbeddingModel openAiEmbeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(ragLlmProperties.getEmbeddingBaseUrl())
                .apiKey(ragLlmProperties.getEmbeddingApiKey())
                .modelName(ragLlmProperties.getEmbeddingModel())
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
     * @return 配置了 DeepSeek 端点、温度 0.7、3 分钟超时的流式模型实例
     */
    @Bean
    public OpenAiStreamingChatModel openAiStreamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                // 主模型端点刻意保持字面量、不配置化：本次迁移不动 DeepSeek（独立 Key，不受 DashScope 停用影响），
                // 无需回滚开关；也让「可配置 = 可能有回滚动机」这一区分保持清晰
                .baseUrl("https://api.deepseek.com")
                .apiKey(ragLlmProperties.getChatApiKey())
                .modelName(ragLlmProperties.getChatModel())
                .temperature(0.7)
                .timeout(Duration.ofMinutes(3))
                // 流式 tool-call ID：实测 DeepSeek 只在第一个 chunk 发完整 id、后续空（符合 OpenAI 标准），
                // 默认 accumulateToolCallId=true 累积正确。⚠️ 误设 false 会把 id 覆盖成空 → 工具回填不匹配死循环。
                .accumulateToolCallId(true)
                .build();
    }

    /**
     * 注册备用流式对话模型 Bean（智谱 glm-4-flash，降级专用）
     * <p>
     * 主模型 DeepSeek 熔断时降级到此模型，仅做【纯 RAG 问答】（不带工具）——
     * 工具调用主用 DeepSeek（实测稳定），备用模型不参与工具调用，规避异构模型工具调用未实测风险（Phase 5 C1）。
     * 2026-09 由 DashScope qwen-plus 换成智谱 glm-4-flash：DashScope 额度到期，
     * **主备异构**的容灾语义保持不变（仍是两家不同供应商）。端点/模型名/Key 全走 {@code rag.llm.backup-*}。
     *
     * @return 读 {@code rag.llm.backup-*} 配置的流式模型实例
     */
    @Bean
    public OpenAiStreamingChatModel openAiStreamingChatModelBackup() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(ragLlmProperties.getBackupBaseUrl())
                .apiKey(ragLlmProperties.getBackupApiKey())
                .modelName(ragLlmProperties.getBackupModel())
                .temperature(0.7)
                .timeout(Duration.ofMinutes(3))
                // 与主模型一致的流式 tool-call 行为（保持默认累积，误设 false 会把 id 覆盖成空）
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
