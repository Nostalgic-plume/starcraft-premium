package com.shop.recproxy;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.mapper.ProductMapper;
import com.shop.model.entity.ProductDO;
import com.shop.rag.search.SkuPriceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 榜单查询代理（商城后端侧）
 *
 * <p>
 * 对应《详细设计说明书》5.4 的商城侧改造清单：商城后端新增首页榜单代理接口，
 * 对外承接用户端请求，内部调用推荐服务并透传身份与凭证；<b>推荐服务不可达时返回商城原生热销榜</b>
 * （上游兜底，降级链路的最后一环）。
 *
 * <p>
 * <b>身份透传约定</b>：用户标识由商城后端从登录态解析后经请求头写入，绝不下发到前端、
 * 也不接受前端指定——这是推荐服务侧「不接收用户标识参数」得以成立的前提。
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecRankProxyService {

    /** 内部调用凭证请求头 */
    private static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    /** 用户标识请求头 */
    private static final String HEADER_USER_ID = "X-User-Id";

    /** 链路标识请求头 */
    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** 原生兜底榜单的取用条数 */
    private static final int NATIVE_RANK_SIZE = 50;

    /** 商品上架状态取值（product.status = 1） */
    private static final int PRODUCT_STATUS_ON_SALE = 1;

    private final RecProxyProperties properties;

    private final ProductMapper productMapper;

    private final ObjectMapper objectMapper;

    /**
     * SKU 维度聚合器（价格区间/库存的唯一来源）
     *
     * <p>原生兜底榜必须补齐价格：正常路径的价格由推荐服务从商城商品数据回填，兜底路径若不补，
     * 降级期间首页会出现整屏 ¥0，违背「降级结果与正常结果结构一致」的约定（详细设计 3.2）。
     * 此处复用 RAG 侧已提取的聚合器，避免再写一份「商品 → 最低价」的聚合逻辑造成两处漂移。
     */
    private final SkuPriceResolver skuPriceResolver;

    /**
     * 查询首页榜单
     *
     * @param snapshotId 快照标识，仅翻页时由前端回传
     * @param pageNum    页码
     * @param pageSize   每页条数
     * @param userId     用户标识（由登录态解析），游客为 null
     * @param traceId    链路标识
     * @return 三段式结果的业务数据部分
     */
    public Map<String, Object> queryRank(String snapshotId, Integer pageNum, Integer pageSize,
                                         Long userId, String traceId) {
        try {
            String url = buildUrl(snapshotId, pageNum, pageSize);
            log.info("调用推荐服务, url: {}, userId: {}, traceId: {}", url, userId, traceId);
            HttpRequest request = HttpRequest.get(url)
                    .header(HEADER_INTERNAL_TOKEN, properties.getInternalToken())
                    .header(HEADER_TRACE_ID, traceId)
                    .timeout(properties.getTimeoutMillis());
            if (userId != null) {
                request.header(HEADER_USER_ID, String.valueOf(userId));
            }

            try (HttpResponse response = request.execute()) {
                String body = response.body();
                if (body == null || body.isEmpty()) {
                    log.warn("推荐服务返回空响应，降级为原生热销榜, traceId: {}", traceId);
                    return nativeRank(pageNum, pageSize);
                }
                // 用 Jackson 解析而非 hutool：hutool 会把 JSON 的 null 解析成 cn.hutool.json.JSONNull，
                // 该类型无法被 Spring 的 Jackson 序列化，导致响应阶段抛 HttpMessageConversionException。
                // 本工程响应中「游客场景的 snapshotId」正是 null，必然触发该问题。
                JsonNode root = objectMapper.readTree(body);
                JsonNode data = root.get("data");
                if (data != null && !data.isNull()) {
                    Map<String, Object> dataMap =
                            objectMapper.convertValue(data, new TypeReference<Map<String, Object>>() { });
                    // 推荐服务也可能返回空列表（如热度榜尚未产出），此时同样切原生热销榜，
                    // 保证首页不会出现空白
                    Object items = dataMap.get("items");
                    if (items instanceof List && !((List<?>) items).isEmpty()) {
                        return dataMap;
                    }
                    log.warn("推荐服务返回空榜单，降级为原生热销榜, traceId: {}", traceId);
                    return nativeRank(pageNum, pageSize);
                }
                // 推荐服务返回错误码（如 1009 凭证非法）时同样走兜底
                log.warn("推荐服务返回异常结果，降级为原生热销榜, code: {}, traceId: {}",
                        root.path("code").asText(), traceId);
                return nativeRank(pageNum, pageSize);
            }
        } catch (Exception e) {
            // 推荐服务不可达：由商城后端返回原生热销榜，避免首页出现空白或请求挂起
            log.error("调用推荐服务失败，降级为原生热销榜, traceId: {}", traceId, e);
            return nativeRank(pageNum, pageSize);
        }
    }

    /**
     * 拼接推荐服务查询地址
     *
     * @param snapshotId 快照标识
     * @param pageNum    页码
     * @param pageSize   每页条数
     * @return 完整 URL
     */
    private String buildUrl(String snapshotId, Integer pageNum, Integer pageSize) {
        StringBuilder url = new StringBuilder(properties.getBaseUrl()).append("/api/rec/home/rank?");
        if (snapshotId != null && !snapshotId.isEmpty()) {
            url.append("snapshotId=").append(snapshotId).append('&');
        }
        if (pageNum != null) {
            url.append("pageNum=").append(pageNum).append('&');
        }
        if (pageSize != null) {
            url.append("pageSize=").append(pageSize);
        }
        return url.toString();
    }

    /**
     * 商城原生热销榜（上游兜底）
     *
     * <p>改造前的首页榜单形态：按商品热度排序的静态列表。此处以上架且有库存的商品按标识升序
     * 作为原生榜单，保证任何情况下首页都有商品可展示。
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 与推荐服务同结构的结果（degraded = 上游兜底）
     */
    private Map<String, Object> nativeRank(Integer pageNum, Integer pageSize) {
        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 50);

        List<Map<String, Object>> items = new ArrayList<>();
        try {
            Page<ProductDO> productPage = productMapper.selectPage(new Page<>(1, NATIVE_RANK_SIZE),
                    new LambdaQueryWrapper<ProductDO>()
                            .eq(ProductDO::getStatus, PRODUCT_STATUS_ON_SALE)
                            .orderByAsc(ProductDO::getId));
            List<ProductDO> records = productPage.getRecords();
            // 批量补齐价格（一次查询，避免逐商品查询）
            Map<Long, BigDecimal[]> priceMap = skuPriceResolver.resolve(
                    records.stream().map(ProductDO::getId).collect(Collectors.toList()));
            for (ProductDO product : records) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("itemId", product.getId());
                item.put("itemName", product.getName());
                item.put("mainImage", product.getMainImage());
                BigDecimal[] range = priceMap.get(product.getId());
                // 无 SKU 的商品取 0：与正常路径字段齐备的约定保持一致，前端按 ¥0 渲染而非留空
                item.put("price", range == null ? BigDecimal.ZERO : range[0]);
                item.put("recallSource", "NATIVE");
                item.put("score", 0.0D);
                items.add(item);
            }
        } catch (Exception e) {
            log.error("原生热销榜查询失败", e);
        }

        int from = Math.min((page - 1) * size, items.size());
        int to = Math.min(from + size, items.size());
        Map<String, Object> data = new HashMap<>(8);
        data.put("snapshotId", null);
        data.put("degraded", "上游兜底");
        data.put("pageNum", page);
        data.put("pageSize", size);
        data.put("total", items.size());
        data.put("hasMore", to < items.size());
        data.put("items", items.subList(from, to));
        return data;
    }
}
