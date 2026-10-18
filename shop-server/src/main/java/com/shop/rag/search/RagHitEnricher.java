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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * <p>
 * 本类承担**两个时机**的「MySQL 侧商品真相查询」，二者是两层防护而非替代：
 * <ul>
 *   <li>{@link #findDisplayableIds}（RRF 合并**前**，候选池清洗）：剔除幽灵索引，
 *       解决「过滤发生在取 topK 之后 → 返回条数不足」；</li>
 *   <li>{@link #enrich}（取 topK **后**，结果富化）：补全展示数据，
 *       并再次强制 {@code status = 上架}，保证最终展示的真实性。</li>
 * </ul>
 * 前置过滤解决条数，回查解决真相 —— 少了任何一个都有缺口。
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
     * 查询候选商品中「当前可展示」的 ID 集合（上架且未逻辑删除）
     * <p>
     * 供检索链路的**候选池清洗**（RRF 合并之前）使用，与 {@link #enrich} 构成两层防护
     * （分工见类注释）。为什么必须有这道过滤：向量库与 MySQL 的一致性靠「索引更新尽力而为」
     * 维持（索引调用一律 try-catch 只 WARN、且与 DB 写入非事务），故向量库可能残留
     * **已下架商品的幽灵索引**；payload 里没有 status 字段，Qdrant 侧无法过滤，
     * 而等到 topK 之后的 {@link #enrich} 才剔除会导致**返回条数不足**。
     * <p>
     * 之所以读 MySQL 实时状态而非 payload 快照：payload 里的字段是**写入时刻的快照**，
     * 而索引只建上架商品，故快照 status 恒为 1，对幽灵索引毫无区分力。
     * <p>
     * 之所以用**正集**语义（返回「可展示的」，由调用方取补集）而非反集（查 {@code status != 上架}）：
     * {@code deleted = 1} 的记录被 MyBatis-Plus 的 {@code @TableLogic} 自动排除出本次查询，
     * 正集能一次覆盖「已下架」与「已逻辑删除」两种失效，反集只能查到前者。
     * <p>
     * ⚠️ <b>异常向上抛出，本方法刻意不 catch</b>（与同类 {@link #loadProducts} 的内部消化风格不同）：
     * 正集语义下「空集合」等价于「候选全部不可展示」，若与「查询失败」共用同一返回值，
     * 调用方会把一次查询抖动误读成「全部剔除」→ 检索返回空结果。
     * 检索链路据此按「异常即不过滤」处理（与库存过滤同一约定）。
     *
     * @param productIds 候选商品 ID（调用方保证非空；允许重复）
     * @return 其中当前上架且未逻辑删除的商品 ID 集合（空集 = 全部不可展示）
     * @throws RuntimeException 查询异常，由调用方决定放行策略
     */
    public Set<Long> findDisplayableIds(Collection<Long> productIds) {
        if (CollUtil.isEmpty(productIds)) {
            return Set.of();
        }
        // 只取 id 列：本方法只做「是否可展示」判定，名称/描述等展示字段由 enrich 在 topK 之后回查
        List<ProductDO> products = productMapper.selectList(
                new LambdaQueryWrapper<ProductDO>()
                        .in(ProductDO::getId, productIds)
                        .eq(ProductDO::getStatus, ProductConstants.STATUS_ON_SHELF)
                        .select(ProductDO::getId));
        if (CollUtil.isEmpty(products)) {
            return Set.of();
        }
        return products.stream()
                .filter(Objects::nonNull)
                .map(ProductDO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
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
