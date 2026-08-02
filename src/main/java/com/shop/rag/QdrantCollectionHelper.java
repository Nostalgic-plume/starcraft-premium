package com.shop.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Qdrant 集合管理工具
 * 统一使用 REST API（端口 6333），避免 gRPC 兼容性问题，确保集合参数（维度、距离）来源一致
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QdrantCollectionHelper {

    private final RagProperties ragProperties;

    /** REST API 基础地址 */
    private static final String QDRANT_REST_URL = "http://localhost:6333/collections/";

    /**
     * 确保集合存在，不存在则创建
     */
    public void ensureCollection() {
        String name = ragProperties.getCollectionName();
        if (exists(name)) {
            log.info("Qdrant 集合 '{}' 已存在，跳过创建", name);
            return;
        }
        log.info("Qdrant 集合 '{}' 不存在，正在创建...", name);
        createCollection(name);
    }

    /**
     * 删除并重建集合（全量重建前调用，确保清除旧脏数据）
     */
    public void recreateCollection() {
        String name = ragProperties.getCollectionName();

        // 删除旧集合
        try {
            URI del = URI.create(QDRANT_REST_URL + name);
            HttpURLConnection conn = (HttpURLConnection) del.toURL().openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            log.info("清理 Qdrant 旧集合, HTTP {}", code);
            conn.disconnect();
        } catch (Exception e) {
            log.warn("删除 Qdrant 集合失败: {}", e.getMessage());
        }

        createCollection(name);
    }

    // ========== 内部方法 ==========

    private boolean exists(String name) {
        try {
            URI uri = URI.create(QDRANT_REST_URL + name);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void createCollection(String name) {
        int dimension = ragProperties.getDimension();
        try {
            URI uri = URI.create(QDRANT_REST_URL + name + "?wait=true");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            String body = String.format("{\"vectors\":{\"size\":%d,\"distance\":\"Cosine\"}}", dimension);
            conn.getOutputStream().write(body.getBytes());
            int code = conn.getResponseCode();
            log.info("创建 Qdrant 集合 '{}' 完成, HTTP {}, 维度: {}", name, code, dimension);
            conn.disconnect();
        } catch (Exception e) {
            log.warn("创建 Qdrant 集合失败: {}", e.getMessage());
        }
    }
}
