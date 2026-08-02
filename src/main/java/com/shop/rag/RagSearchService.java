package com.shop.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * RAG 混合检索服务（Hybrid Search）
 * <p>
 * 双路并行检索 + RRF 合并：
 * ┌─ DashScope embedding → Qdrant 向量检索 ─┐
 * │                                           │→ RRF 合并 → Top-K
 * └─ MySQL FULLTEXT 关键词检索 ─────────────┘
 * <p>
 * 注意：直接使用 Qdrant REST API（端口 6333）而非 gRPC（端口 6334），
 * 因为 langchain4j-qdrant 0.36.1 依赖的 qdrant-client 1.11.0 与 Qdrant 服务端 1.18.3
 * 的 SearchPoints protobuf 消息格式不兼容（repeated float → optional Vector），
 * 导致检索时"Length of vector a (0)" 错误。REST API 绕过了 gRPC 序列化路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagSearchService {

    private final OpenAiEmbeddingModel embeddingModel;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;
    private final KeywordSearchService keywordSearchService;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final double MIN_RELEVANCE_SCORE = 0.3;
    private static final int EXPECTED_DIMENSION = 1536;

    /**
     * 混合搜索商品（向量 + 关键词双路 → RRF 合并）
     *
     * @param query      用户查询
     * @param topK       返回条数
     * @param categoryId 分类过滤（可选）
     * @param minPrice   最低价（可选）
     * @param maxPrice   最高价（可选）
     * @return 混合匹配结果列表（按 RRF 分数降序）
     */
    public List<RagHitVO> search(String query, int topK,
                                  Long categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        // 1. 生成查询向量
        Embedding queryEmbedding;
        try {
            List<Embedding> embeddings = embeddingModel.embedAll(List.of(TextSegment.from(query))).content();
            if (embeddings == null || embeddings.isEmpty()) {
                log.warn("嵌入模型返回空列表");
                return Collections.emptyList();
            }
            queryEmbedding = embeddings.get(0);
        } catch (Exception e) {
            log.warn("嵌入模型调用失败: {}", e.getMessage());
            return Collections.emptyList();
        }

        // 2. 校验向量
        if (queryEmbedding == null || queryEmbedding.vector() == null || queryEmbedding.vector().length == 0) {
            log.warn("嵌入模型返回空向量，跳过 RAG 检索");
            return Collections.emptyList();
        }
        if (queryEmbedding.vector().length != EXPECTED_DIMENSION) {
            log.warn("向量维度不匹配：期望 {}，实际 {}，跳过 RAG 检索",
                    EXPECTED_DIMENSION, queryEmbedding.vector().length);
            return Collections.emptyList();
        }

        // 3. 双路并行检索：向量 + 关键词
        int queryLimit = Math.max(topK * 5, 50);

        List<RagHitVO> vectorHits = searchViaQdrantRest(queryEmbedding.vector(), queryLimit,
                MIN_RELEVANCE_SCORE, categoryId, minPrice, maxPrice, topK);

        List<RagHitVO> keywordHits = keywordSearchService.searchByKeyword(query, queryLimit);

        log.debug("混合检索: 向量命中 {}, 关键词命中 {}, 合并取 Top-{}",
                vectorHits.size(), keywordHits.size(), topK);

        // 4. RRF 合并
        return RrfMerger.merge(vectorHits, keywordHits, topK);
    }

    /**
     * 直接调用 Qdrant REST API 进行向量搜索
     * <p>
     * POST /collections/{name}/points/search
     */
    private List<RagHitVO> searchViaQdrantRest(float[] vector,
                                                int queryLimit, double minScore,
                                                Long categoryId, BigDecimal minPrice, BigDecimal maxPrice,
                                                int topK) {
        try {
            // 构建请求体
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", floatArrayToList(vector));
            body.put("limit", queryLimit);
            body.put("score_threshold", minScore);
            body.put("with_payload", true);
            body.put("with_vector", false);

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:6333/collections/"
                            + ragProperties.getCollectionName() + "/points/search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Qdrant REST API 返回非200: {} - {}",
                        response.statusCode(), truncate(response.body(), 200));
                return Collections.emptyList();
            }

            // 解析响应
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode result = root.get("result");
            if (result == null || !result.isArray() || result.isEmpty()) {
                return Collections.emptyList();
            }

            // 遍历结果，过滤元数据，按 topK 截断
            List<RagHitVO> hits = new ArrayList<>();
            for (JsonNode point : result) {
                if (hits.size() >= topK) break;

                try {
                    double score = point.get("score").asDouble();
                    JsonNode payload = point.get("payload");
                    if (payload == null || payload.isEmpty()) continue;

                    // 业务元数据过滤（分类/价格）
                    if (!passMetadataFilter(payload, categoryId, minPrice, maxPrice)) continue;

                    // 构建 VO
                    RagHitVO vo = RagHitVO.builder()
                            .score((double) Math.round(score * 100) / 100)
                            .excerpt(truncate(safeText(payload, "text_segment"), 120))
                            .productId(safeLong(payload, "productId"))
                            .name(safeText(payload, "name"))
                            .image(safeText(payload, "mainImage"))
                            .minPrice(safeBigDecimal(payload, "minPrice"))
                            .maxPrice(safeBigDecimal(payload, "maxPrice"))
                            .build();

                    hits.add(vo);

                } catch (Exception e) {
                    log.debug("解析 Qdrant 搜索结果项异常: {}", e.getMessage());
                }
            }

            return hits;

        } catch (Exception e) {
            log.warn("Qdrant REST API 检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ========== 元数据过滤 ==========

    /** 按分类 ID + 价格区间过滤搜索结果 */
    private boolean passMetadataFilter(JsonNode payload, Long categoryId,
                                        BigDecimal minPrice, BigDecimal maxPrice) {
        // 分类过滤
        if (categoryId != null) {
            JsonNode catNode = payload.get("firstCategoryId");
            if (catNode != null && !catNode.isNull()) {
                String catVal = catNode.asText();
                if (!String.valueOf(categoryId).equals(catVal)) {
                    return false;
                }
            }
        }

        // 最低价过滤（商品的 maxPrice 必须 >= 查询的 minPrice）
        if (minPrice != null) {
            BigDecimal maxP = safeBigDecimal(payload, "maxPrice");
            if (maxP != null && maxP.compareTo(minPrice) < 0) {
                return false;
            }
        }

        // 最高价过滤（商品的 minPrice 必须 <= 查询的 maxPrice）
        if (maxPrice != null) {
            BigDecimal minP = safeBigDecimal(payload, "minPrice");
            if (minP != null && minP.compareTo(maxPrice) > 0) {
                return false;
            }
        }

        return true;
    }

    // ========== JSON 安全取值辅助 ==========

    private static String safeText(JsonNode payload, String key) {
        JsonNode node = payload.get(key);
        if (node == null || node.isNull()) return null;
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static Long safeLong(JsonNode payload, String key) {
        JsonNode node = payload.get(key);
        if (node == null || node.isNull()) return null;
        if (node.canConvertToLong()) return node.asLong();
        if (node.isTextual()) {
            try {
                return Long.parseLong(node.asText());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static BigDecimal safeBigDecimal(JsonNode payload, String key) {
        JsonNode node = payload.get(key);
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) {
            double val = node.asDouble();
            // 浮点数精确转换，避免精度损失
            return BigDecimal.valueOf(val);
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    // ========== 工具方法 ==========

    /** float[] → List<Float>，用于 JSON 序列化 */
    private static List<Float> floatArrayToList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
