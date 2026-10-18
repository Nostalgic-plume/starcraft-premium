package com.shop.rag.index;

import com.shop.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
     * 过滤下推所需的 payload 索引定义：字段名 → Qdrant field_schema
     * <p>
     * ⚠️ 这些索引是「过滤下推」的**前置条件**：Qdrant 对没有 payload 索引的字段做过滤是全量扫描，
     * 下推不但不会更快，还可能比应用层过滤更慢。新增可过滤字段时必须同步在这里登记。
     * <p>
     * 类型说明：{@code firstCategoryId} 为 Long → integer；价格在 payload 里是 double
     * （{@code BigDecimal.doubleValue()}）→ float。类型不匹配会导致过滤结果为空。
     */
    private static final Map<String, String> PAYLOAD_INDEXES = Map.of(
            "firstCategoryId", "integer",
            "minPrice", "float",
            "maxPrice", "float");

    /**
     * 从集合信息响应中提取向量维度（config.params.vectors.size）
     * <p>
     * 限定 "vectors" 前缀，避免误匹配响应中的其它 size 字段。
     */
    private static final Pattern VECTOR_SIZE_PATTERN =
            Pattern.compile("\"vectors\"\\s*:\\s*\\{\\s*\"size\"\\s*:\\s*(\\d+)");

    /**
     * 确保集合存在，不存在则创建
     */
    public void ensureCollection() {
        String name = ragProperties.getCollectionName();
        if (exists(name)) {
            log.info("Qdrant 集合 '{}' 已存在，跳过创建", name);
            verifyDimension(name);
            // 存量集合可能建于过滤下推改造之前（无 payload 索引）—— 幂等补建，无需重建集合
            ensurePayloadIndexes(name);
            return;
        }
        log.info("Qdrant 集合 '{}' 不存在，正在创建...", name);
        createCollection(name);
    }

    /**
     * 校验已存在集合的向量维度与配置一致
     * <p>
     * 为什么必须校验：换 embedding 模型（如 1536 维 → 1024 维）时，集合维度**无法修改**，
     * 只能删了重建；而 {@link #ensureCollection()} 见集合存在即跳过、索引器见 point 数 >0 也跳过，
     * 集合会停留在旧维度。此时写入/检索都会因维度不符失败，RagSearchService 会 catch 后
     * **静默退化为纯关键词检索**（仅一条 WARN），管理页仍显示 indexedCount 正常 —— 极难排查。
     * <p>
     * 这里只**告警**、不自动重建：删集合是破坏性操作，必须由人显式触发
     * （{@code POST /api/admin/rag/rebuild}），避免"为了修一个问题而删掉全部向量"。
     *
     * @param name 集合名称
     */
    private void verifyDimension(String name) {
        Integer actual = fetchVectorSize(name);
        if (actual == null) {
            log.warn("无法读取 Qdrant 集合 '{}' 的向量维度（集合信息解析失败或 Qdrant 不可用），跳过维度校验", name);
            return;
        }
        int expected = ragProperties.getDimension();
        if (actual != expected) {
            log.error("Qdrant 集合 '{}' 向量维度不匹配：集合实际 {} 维，配置 {} 维（rag.qdrant.dimension）。"
                            + "向量路将整体失效（检索时维度校验不通过会静默跳过向量路，退化为纯关键词检索），"
                            + "请执行全量重建：POST /api/admin/rag/rebuild",
                    name, actual, expected);
        } else {
            log.info("Qdrant 集合 '{}' 向量维度校验通过: {} 维", name, actual);
        }
    }

    /**
     * 读取集合的向量维度（REST GET /collections/{name}）
     *
     * @param name 集合名称
     * @return 向量维度；Qdrant 不可用、集合不存在或响应无法解析时返回 null
     */
    private Integer fetchVectorSize(String name) {
        try {
            URI uri = URI.create(QDRANT_REST_URL + name);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return null;
            }
            String json = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();

            Matcher matcher = VECTOR_SIZE_PATTERN.matcher(json);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
        } catch (Exception e) {
            log.warn("读取 Qdrant 集合维度异常: {}", e.getMessage());
            return null;
        }
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

    /**
     * 确保过滤字段的 payload 索引存在（过滤下推的前置条件）
     * <p>
     * 幂等：已存在的索引重复创建由 Qdrant 返回 "already exists"，不视为错误；
     * 在**已有数据**的集合上创建索引由 Qdrant 后台完成，**无需删集合重建**。
     * <p>
     * 单个字段失败只 WARN，不阻断其余字段 —— 索引缺失时过滤仍可用（降级为全量扫描）。
     *
     * @param name 集合名称
     */
    private void ensurePayloadIndexes(String name) {
        PAYLOAD_INDEXES.forEach((field, schema) -> createPayloadIndex(name, field, schema));
    }

    /**
     * 创建单个 payload 索引（REST PUT，幂等）
     *
     * @param name   集合名称
     * @param field  payload 字段名
     * @param schema Qdrant field_schema（integer / float / keyword ...）
     */
    private void createPayloadIndex(String name, String field, String schema) {
        try {
            URI uri = URI.create(QDRANT_REST_URL + name + "/index?wait=true");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            // 在已有数据上建索引需要扫描 payload，读超时放宽（670 条实测毫秒级，为增长留余量）
            conn.setReadTimeout(10000);
            String body = String.format("{\"field_name\":\"%s\",\"field_schema\":\"%s\"}", field, schema);
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int code = conn.getResponseCode();
            if (code == 200) {
                log.info("Qdrant payload 索引 '{}' ({}) 就绪", field, schema);
            } else {
                log.warn("Qdrant payload 索引 '{}' 创建返回 HTTP {}", field, code);
            }
            conn.disconnect();
        } catch (Exception e) {
            // 索引缺失不阻断检索（降级为全量扫描），故只告警
            log.warn("创建 Qdrant payload 索引 '{}' 失败: {}", field, e.getMessage());
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
            // 新集合必须同步建好过滤字段的 payload 索引（过滤下推的前置条件）
            ensurePayloadIndexes(name);
        } catch (Exception e) {
            log.warn("创建 Qdrant 集合失败: {}", e.getMessage());
        }
    }
}
