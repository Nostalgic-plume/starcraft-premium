package com.shop.rag.index;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 临时裁决测试：验证 qdrant-client 1.17.0 与 Qdrant 服务端的 gRPC 兼容性。
 *
 * 背景：langchain4j 0.36.1 依赖 qdrant-client 1.11.0，与服务端 1.18.3 的 gRPC
 * SearchPoints 协议不兼容，检索时抛 "Length of vector a (0)"，项目被迫改手写 REST。
 * 本次升级到 1.19.0（qdrant-client 1.17.0，与服务端差 1 个 minor，官方保证兼容），
 * 本测试直测官方 gRPC search() 路径是否已修复。
 *
 * 设计：不依赖 Spring 上下文与 embedding 服务——用假 1536 维向量直测，
 * 使 gRPC 兼容性成为唯一变量。PASS = 坑已修复；FAIL = 触发次选方案（Docker 对齐版本）。
 */
class QdrantGrpcCompatibilityTest {

    @Test
    void grpcSearch_shouldNotThrowVectorLengthError() {
        QdrantEmbeddingStore store = QdrantEmbeddingStore.builder()
                .host("localhost")
                .port(6334)            // gRPC 端口
                .collectionName("products")
                .build();

        // 假 1024 维向量（对应 products 集合维度 BGE-M3，绕过 embedding 服务依赖）
        float[] vector = new float[1024];
        Random random = new Random(42);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = random.nextFloat();
        }
        Embedding embedding = Embedding.from(vector);

        // 官方 gRPC search()——0.36.1 在此抛 "Length of vector a (0)"
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embedding)
                .maxResults(5)
                .build();

        EmbeddingSearchResult<TextSegment> result = assertDoesNotThrow(
                () -> store.search(request));

        List<EmbeddingMatch<TextSegment>> matches = result.matches();
        System.out.println("gRPC search 成功，命中 " + matches.size() + " 条");
        assertNotNull(matches);
    }
}
