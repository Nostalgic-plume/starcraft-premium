package com.shop.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Qdrant 集合管理工具
 * 统一使用 REST API（端口 6333），避免 gRPC 兼容性问题，确保集合参数（维度、距离）来源一致
 *
 * @author shop
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QdrantCollectionHelper {

    private final RagProperties ragProperties;

    /** REST API 基础地址 */
    private static final String QDRANT_REST_URL = "http://localhost:6333/collections/";

    /** 从 count 接口响应中提取 count 字段 */
    private static final Pattern COUNT_PATTERN = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");

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

    /**
     * 统计集合内 point 数量（REST POST /collections/{name}/points/count）
     * <p>
     * 集合名取自配置，避免各调用方硬编码；Qdrant 不可用或集合不存在时返回 0，调用方按「无数据」处理。
     *
     * @return 集合内 point 数量
     */
    public long countPoints() {
        String name = ragProperties.getCollectionName();
        try {
            URI uri = URI.create(QDRANT_REST_URL + name + "/points/count");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.getOutputStream().write("{\"exact\":true}".getBytes(StandardCharsets.UTF_8));

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                log.warn("查询 Qdrant point 数量失败, 集合 '{}', HTTP {}", name, code);
                return 0;
            }
            String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();

            Matcher matcher = COUNT_PATTERN.matcher(json);
            return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
        } catch (Exception e) {
            log.warn("查询 Qdrant point 数量异常: {}", e.getMessage());
            return 0;
        }
    }

    // ========== 内部方法 ==========

    /**
     * 检查 Qdrant 集合是否存在（REST GET，3 秒超时）
     *
     * @param name 集合名称
     * @return 集合存在（HTTP 200）返回 true；不存在或网络/解析异常返回 false
     */
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

    /**
     * 创建 Qdrant 集合（REST PUT，向量维度取自配置，距离算法 Cosine）
     * <p>
     * 创建失败仅记录警告日志，不抛出异常，由上层容错处理。
     *
     * @param name 集合名称
     */
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
