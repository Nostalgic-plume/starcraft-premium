package com.shop.rag.search;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.constant.ProductConstants;
import com.shop.mapper.ProductMapper;
import com.shop.model.entity.ProductDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 检索结果富化器：把「候选 ID」补全为「可直接展示的命中项」
 * <p>
 * 检索链路的召回阶段只产出 {@link RagCandidate}（商品 ID + 分数），展示数据由本类在
 * **选出 topK 之后**统一回查 MySQL —— 回查量从候选池（50~200 条）降到 topK（3~10 条）。
 * <p>
 * 两条路（向量 / 关键词）的 {@code excerpt} 由此统一为「商品描述的截断」，
 * 消除了此前「向量路带『名称：/描述：/品牌：』标签前缀、关键词路是纯描述」的语义不一致 ——
 * 该差异会让客服卡片在 40 字配额里显示成「名称：xxx 描述：」而挤掉全部有效描述。
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagHitEnricher {

    /** 摘要最大长度（与改造前向量路的 excerpt 保持一致） */
    private static final int MAX_EXCERPT_LENGTH = 120;

    private final ProductMapper productMapper;
    private final SkuPriceResolver skuPriceResolver;

    /**
     * 批量富化候选：回查商品展示字段与 SKU 价格
     * <p>
     * 保持入参顺序 —— RRF 已排好序，而批量查询的返回顺序不保证与入参一致，
     * 必须按 productId 映射回原序，否则融合排序白做。
     * <p>
     * 查不到（已删除 / 已下架）或 ID 为 null 的候选**跳过**；商品查询整体异常时返回空列表。
     *
     * @param candidates RRF 融合后、已截断至 topK 的候选
     * @return 同序的完整命中项列表（可能少于入参条数）
     */
    public List<RagHitVO> enrich(List<RagCandidate> candidates) {
        if (CollUtil.isEmpty(candidates)) {
            return List.of();
        }

        List<Long> productIds = candidates.stream()
                .map(RagCandidate::productId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (productIds.isEmpty()) {
            return List.of();
        }

        Map<Long, ProductDO> productMap = loadProducts(productIds);
        if (productMap.isEmpty()) {
            log.warn("检索结果富化：商品批量查询无结果，本次不返回任何命中项");
            return List.of();
        }
        // SKU 快照不可用时价格为空，但商品仍照常返回（价格非必需展示项）
        Map<Long, SkuSnapshot> snapshotMap = skuPriceResolver.resolveSnapshot(productIds);

        List<RagHitVO> hits = new ArrayList<>(candidates.size());
        for (RagCandidate candidate : candidates) {
            Long productId = candidate.productId();
            ProductDO product = productId == null ? null : productMap.get(productId);
            if (product == null) {
                // 商品已被删除 / 下架 / 查不到 —— 跳过，不返回残缺条目
                continue;
            }
            SkuSnapshot snapshot = snapshotMap.get(productId);
            hits.add(RagHitVO.builder()
                    .score(candidate.score())
                    .productId(productId)
                    .name(product.getName())
                    .image(product.getMainImage())
                    .excerpt(truncate(product.getDescription(), MAX_EXCERPT_LENGTH))
                    .minPrice(snapshot == null ? null : snapshot.minPrice())
                    .maxPrice(snapshot == null ? null : snapshot.maxPrice())
                    .build());
        }
        return hits;
    }

    /**
     * 批量查询商品展示字段（仅取必要列，不读 detail 等大字段）
     * <p>
     * 强制 {@code status = 上架}：从检索发生到回查之间商品可能已下架，此时不应再展示。
     * 查询异常时返回空 Map，由调用方按「无命中」处理。
     *
     * @param productIds 商品 ID 列表（非空）
     * @return productId → 商品（仅含展示所需字段）
     */
    private Map<Long, ProductDO> loadProducts(List<Long> productIds) {
        try {
            List<ProductDO> products = productMapper.selectList(
                    new LambdaQueryWrapper<ProductDO>()
                            .in(ProductDO::getId, productIds)
                            .eq(ProductDO::getStatus, ProductConstants.STATUS_ON_SHELF)
                            .select(ProductDO::getId, ProductDO::getName,
                                    ProductDO::getMainImage, ProductDO::getDescription));
            if (CollUtil.isEmpty(products)) {
                return Map.of();
            }
            return products.stream()
                    .filter(Objects::nonNull)
                    .filter(product -> product.getId() != null)
                    .collect(Collectors.toMap(ProductDO::getId, product -> product, (first, ignored) -> first));
        } catch (Exception e) {
            log.warn("检索结果富化：商品批量查询异常: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 文本截断（超长补省略号） */
    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
