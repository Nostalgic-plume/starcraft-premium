package com.shop.rag.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 对话 / 向量化端点配置（rag.llm.*）
 * <p>
 * 为什么用 properties 类而非散落的 {@code @Value}：{@code rag.qdrant} / {@code rag.rrf} /
 * {@code rag.synonym} 都已收敛为 properties 类，{@code rag.llm} 是唯一例外；更关键的是
 * {@code @Value} 无默认值回退时，缺环境变量会直接导致 Spring 上下文启动失败
 * （唯一 {@code @SpringBootTest} RagEvaluationTest 首当其冲）。且当占位符链上游键
 * 「存在但为空」时（如 DASHSCOPE_API_KEY 退役后 {@code ${DEEPSEEK_API_KEY:${rag.llm.api-key}}}
 * 会解析为 {@code ""} 而非缺失），{@code ${x:默认值}} 的默认分支不会生效 —— 这类空值
 * 只能在 Java 侧归一化。
 * <p>
 * 主对话模型（DeepSeek）的 base-url 刻意不在此类中：本次迁移不动主模型，
 * 其端点保持 RagConfig 内字面量。
 *
 * @author shop
 */
@Data
@Slf4j
@Component
@ConfigurationProperties(prefix = "rag.llm")
public class RagLlmProperties {

    /** DashScope Key（保留，仅作回滚用；不再被 embedding / backup 引用） */
    private String apiKey;

    // ── 主对话模型（本次不改语义，仅补缺键兜底）──

    /** 主对话模型 Key（优先 DEEPSEEK_API_KEY，未配回退 apiKey） */
    private String chatApiKey;

    /** 主对话模型名 */
    private String chatModel = "deepseek-chat";

    // ── 向量化模型（本地 TEI 部署的 BGE-M3，1024 维）──

    /** 向量化端点（OpenAI 兼容）。默认指向本地 TEI（9090）；换其他兼容服务只改此 URL */
    private String embeddingBaseUrl = "http://localhost:9090/v1";

    /** 向量化 Key：TEI 不校验鉴权，但 SDK 要求非空，故给占位值 */
    private String embeddingApiKey = "dummy";

    /** 向量化模型名（须与实际加载的模型一致；TEI 挂载 BAAI/bge-m3，输出 1024 维） */
    private String embeddingModel = "bge-m3";

    // ── 备用对话模型（异构容灾：与主模型 DeepSeek 不同厂商）──

    /** 备用模型端点（智谱开放平台，OpenAI 兼容） */
    private String backupBaseUrl = "https://open.bigmodel.cn/api/paas/v4";

    /** 备用模型 Key */
    private String backupApiKey;

    /** 备用模型名 */
    private String backupModel = "glm-4-flash";

    /**
     * 空值归一化 + 启动期回显实际生效端点
     * <p>
     * 归一化：空串 / 未解析的占位符一律兜底为占位值，避免各 builder 对空 key 抛
     * IllegalArgumentException 导致上下文启动失败（宁可调用时 401，也不要起不来）。
     * 回显：让「改了配置没生效」这类问题在启动日志就能看到线索。
     * <p>
     * 包可见以便单测在无 Spring 上下文时手动调用。
     */
    @PostConstruct
    void normalizeAndLog() {
        chatApiKey = firstNonBlank(chatApiKey, apiKey, "dummy");
        embeddingApiKey = firstNonBlank(embeddingApiKey, "dummy");
        backupApiKey = firstNonBlank(backupApiKey, "dummy");

        log.info("RAG 端点配置: embedding={} model={} | backup={} model={} | chatModel={}",
                embeddingBaseUrl, embeddingModel, backupBaseUrl, backupModel, chatModel);
        if ("dummy".equals(chatApiKey)) {
            log.warn("rag.llm.chat-api-key 解析为空（已用占位值兜底），主对话模型实际调用会 401，请检查 DEEPSEEK_API_KEY");
        } else if (!isBlank(apiKey) && apiKey.equals(chatApiKey)) {
            // 占位符链 ${DEEPSEEK_API_KEY:${rag.llm.api-key}} 在 DEEPSEEK_API_KEY 缺失时
            // 会**静默解析为 DashScope 的 Key**（非空、也不等于 "dummy"），因此「等于 dummy」
            // 这一条告警条件覆盖不到它。必须按「值与 DashScope Key 相同」判定，
            // 否则主模型会带着错误厂商的 Key 调用 → 401 → 连续 3 次熔断 → 降级到备用模型，
            // 运维在日志里看不到任何线索（这正是本类注释声称要防的「配了但没生效」）。
            log.warn("rag.llm.chat-api-key 回退到了 DashScope Key（rag.llm.api-key）："
                    + "该 Key 与 DeepSeek 端点不匹配，主对话模型调用会 401 并触发熔断降级，请配置 DEEPSEEK_API_KEY");
        }
        if ("dummy".equals(backupApiKey)) {
            log.warn("rag.llm.backup-api-key 未配置（已用占位值兜底），熔断降级到备用模型时会 401，降级链第 3 环不可用");
        }
    }

    /**
     * 空值判定（含空白串）
     *
     * @param value 待判定值
     * @return null 或空白返回 true
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 返回首个非空白候选值
     *
     * @param candidates 候选值（可含 null / 空白）
     * @return 首个非空白值；全部为空时返回 null
     */
    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }
}
