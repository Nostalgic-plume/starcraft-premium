package com.shop.rag.index;

import com.shop.constant.ProductConstants;
import com.shop.model.entity.ProductDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 商品索引同步门面（功能编号 001）
 * <p>
 * 商品写操作的唯一索引入口，统一扇出到两个**互相独立**的索引：
 * <ul>
 *   <li>{@link ProductIndexer} —— Qdrant 向量索引（语义检索路）</li>
 *   <li>{@link EsProductIndexer} —— Elasticsearch 文档索引（关键词检索路）</li>
 * </ul>
 * <p>
 * 为什么需要门面：两个索引器的失败必须彼此隔离（一个挂了不能连累另一个），
 * 且调用方（商品服务 / 对账任务 / 管理接口）不应该知道有几个索引、谁先谁后。
 * 任一索引失败只 WARN，不阻断商品主流程 —— 与改造前 Qdrant 索引的约定一致。
 * <p>
 * 一致性兜底：失败的增量同步由全量重建修复（{@code /api/admin/rag/rebuild}）；
 * ES 侧另有检索时的 {@code status=1 AND deleted=0} 过滤兜住幽灵文档。
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductIndexSyncService {

    private final ProductIndexer productIndexer;
    private final EsProductIndexer esProductIndexer;

    /**
     * 新增 / 更新商品索引（非上架商品按删除处理，保证两端语义一致）
     *
     * @param product 商品
     */
    public void addOrUpdate(ProductDO product) {
        if (product == null) {
            return;
        }
        if (product.getStatus() == null || product.getStatus() != ProductConstants.STATUS_ON_SHELF) {
            // 门面统一分流：Qdrant 与 ES 都按「删除」处理，避免一端残留造成两路结果不一致
            remove(product.getId());
            return;
        }
        try {
            productIndexer.addOrUpdate(product);
        } catch (Exception e) {
            log.warn("Qdrant 索引同步异常（不影响主流程）: {}", e.getMessage());
        }
        try {
            esProductIndexer.upsert(product);
        } catch (Exception e) {
            log.warn("ES 索引同步异常（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 删除商品索引（两端都删；不存在的文档静默忽略）
     *
     * @param productId 商品 ID
     */
    public void remove(Long productId) {
        if (productId == null) {
            return;
        }
        try {
            productIndexer.remove(productId);
        } catch (Exception e) {
            log.warn("Qdrant 索引删除异常（不影响主流程）: {}", e.getMessage());
        }
        try {
            esProductIndexer.delete(productId);
        } catch (Exception e) {
            log.warn("ES 索引删除异常（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 全量重建两个索引（管理后台手动触发）
     * <p>
     * Qdrant 重建包含逐批向量化，耗时较长；ES 重建是纯 bulk 写入，通常快得多。
     * 两者串行执行，任一失败不影响另一个（各自内部已兜底并记录状态）。
     */
    public void forceRebuildAll() {
        log.info("===== 开始全量重建商品索引（Qdrant + ES）=====");
        try {
            productIndexer.forceRebuildAll();
        } catch (Exception e) {
            log.error("Qdrant 索引重建失败: {}", e.getMessage(), e);
        }
        try {
            esProductIndexer.rebuildAll();
        } catch (Exception e) {
            log.error("ES 索引重建失败: {}", e.getMessage(), e);
        }
    }
}
