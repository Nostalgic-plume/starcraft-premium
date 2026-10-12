package com.shop.rag.search;

/**
 * 检索候选（融合阶段的最小契约）
 * <p>
 * 只携带「融合排序」真正需要的两个字段：商品 ID 与相关性分数。
 * 名称、主图、描述、价格等展示数据**不在本类型内**，由 {@link RagHitEnricher}
 * 在选出 topK 之后统一回查 MySQL 补齐 —— 这是「召回阶段只认 ID」的落点。
 * <p>
 * 为什么不复用 {@link RagHitVO} 做中间态：那会让它在中途出现「展示字段全为 null」的状态，
 * 调用方难以分辨「该字段尚未回查」与「该字段确实为空」，容易误用。
 *
 * @param productId 商品 ID
 * @param score     相关性分数（向量路为相似度，关键词路为位次模拟分，融合后为 RRF 分数）
 * @author shop
 */
public record RagCandidate(Long productId, double score) {
}
