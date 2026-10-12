package com.shop.rag.index;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.constant.ProductConstants;
import com.shop.mapper.ProductMapper;
import com.shop.model.entity.ProductDO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全量重建索引的状态上报验证（真实重建一次）
 * <p>
 * 覆盖 code-review F4：重建结束后必须能区分「成功」与「失败后集合被清空」。
 * {@code doRebuild()} 首步 {@code recreateCollection()} 会**先删掉集合**，
 * 失败早返回时集合已空 —— 若只看时间戳，会把这种情况显示成「重建完成」，
 * 而此刻向量检索完全不可用。
 * <p>
 * 覆盖范围说明：本测试只走**成功路径**。失败路径需要向量化服务不可用
 * （停 TEI 或断网），属破坏性人工验证场景，不适合放进常规测试 ——
 * 停 TEI 会真的触发「删集合 → 全空 → 失败返回」，需要随后重建才能恢复。
 * 失败分支的正确性由代码路径保证：三条出口（无上架商品 / 全部空向量 / 成功）
 * 均已改为显式调用 {@code markRebuildFinished(success, message)}。
 */
@SpringBootTest
class ProductIndexerRebuildTest {

    @Autowired
    private ProductIndexer productIndexer;

    @Autowired
    private ProductMapper productMapper;

    @Test
    void rebuildReportsSuccessAndIndexesAllOnShelfProducts() {
        long onShelf = productMapper.selectCount(
                new LambdaQueryWrapper<ProductDO>()
                        .eq(ProductDO::getStatus, ProductConstants.STATUS_ON_SHELF));
        assertTrue(onShelf > 0, "前置：库中应有在售商品，否则本测试无意义");

        productIndexer.forceRebuildAll();

        assertTrue(productIndexer.isLastRebuildSuccess(),
                "重建应上报成功，实际 message=" + productIndexer.getLastRebuildMessage());
        assertTrue(productIndexer.getLastRebuildTime() > 0, "重建结束时间应被记录");
        assertEquals((int) onShelf, productIndexer.getIndexedCount(),
                "索引条数应等于在售商品数（同时验证批量预加载品牌未破坏索引构建）");
        assertTrue(productIndexer.getLastRebuildMessage().contains("重建成功"),
                "成功说明应可读，实际=" + productIndexer.getLastRebuildMessage());
    }
}
