package com.shop.rag.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 语义搜索接口的 excerpt 契约验证（「只召回 ID + 回查」改造的端到端验收）
 * <p>
 * 改造前两条路的 {@code excerpt} 语义不一致：向量路取 Qdrant payload 里的索引文本前 120 字
 * （带「名称：/描述：/品牌：/分类：/详情：」标签），关键词路取纯商品描述。客服卡片会把 excerpt
 * 再截到 40 字，标签与标题于是占满配额，有效描述一个字都进不去。
 * <p>
 * 改造后展示数据统一由 {@link RagHitEnricher} 回查 MySQL，两路 excerpt 都应是**纯商品描述**。
 * 本测试经完整 MVC 栈（含 Jackson 序列化）调用真实接口，锁定这条对外契约不被改回去 ——
 * 单元测试用 mock 数据无法防住「索引侧又把标签写进 payload」。需要 Qdrant + 向量化服务 +
 * 商品库都在位，环境缺失时自动跳过（与 {@link RagEvaluationTest} 同一策略）。
 *
 * @author shop
 */
@SpringBootTest
@AutoConfigureMockMvc
class RagSearchExcerptContractTest {

    /** 索引文本的段标签 —— 出现在 excerpt 里即为回退（展示数据又被 payload 供给了） */
    private static final List<String> INDEX_TEXT_LABELS =
            List.of("名称：", "描述：", "品牌：", "分类：", "详情：");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void requireQdrant() {
        Assumptions.assumeTrue(qdrantReachable(), "Qdrant 未运行，跳过 excerpt 契约验证");
    }

    @Test
    @DisplayName("rag-search：excerpt 为纯商品描述（无索引标签前缀），名称/图片/价格是独立字段")
    void excerptShouldBePlainDescriptionWithoutLabels() throws Exception {
        // 两个查询词：一个偏向量语义（口语化），一个偏关键词精确匹配，覆盖两条路的命中项
        for (String query : List.of("手机", "vivo")) {
            JsonNode hits = search(query);
            assertTrue(hits.size() > 0, "查询「" + query + "」应有命中项，否则本用例无法验证 excerpt");

            for (JsonNode hit : hits) {
                String excerpt = hit.path("excerpt").asText("");
                assertFalse(excerpt.isBlank(), "命中项 excerpt 不应为空: " + hit);

                for (String label : INDEX_TEXT_LABELS) {
                    assertFalse(excerpt.contains(label),
                            "excerpt 不应含索引标签前缀「" + label + "」，实际=" + excerpt);
                }

                // 展示数据是独立字段，不应挤进 excerpt（名称空则说明回查链路断了）
                assertFalse(hit.path("name").asText("").isBlank(), "name 应为独立字段: " + hit);
                assertFalse(hit.path("productId").asText("").isBlank(), "productId 应存在: " + hit);
            }
        }
    }

    /** 调真实接口并返回 data.hits 节点 */
    private JsonNode search(String query) throws Exception {
        String body = mockMvc.perform(get("/api/product/rag-search")
                        .param("q", query)
                        .param("topK", "3"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        System.out.println("[E2E] GET /api/product/rag-search?q=" + query + " → " + body);

        JsonNode root = objectMapper.readTree(body);
        assertEqualsOk(root);
        return root.path("data").path("hits");
    }

    /** 统一返回体 code=200 为成功（见 Result#success） */
    private static void assertEqualsOk(JsonNode root) {
        int code = root.path("code").asInt(-1);
        assertTrue(code == 200, "接口应返回成功（code=200），实际响应=" + root);
    }

    private static boolean qdrantReachable() {
        try {
            URI uri = URI.create("http://localhost:6333/collections/products");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
