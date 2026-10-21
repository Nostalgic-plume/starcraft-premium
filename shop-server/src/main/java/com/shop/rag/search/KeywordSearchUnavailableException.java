package com.shop.rag.search;

/**
 * ES 关键词检索不可用（连接失败 / 超时 / 索引不存在 / 5xx）
 * <p>
 * 与「命中 0 条」严格区分：空结果返回空列表，不可用必须抛异常，
 * 由 {@link KeywordSearchRouter} 捕获后回退 MySQL FULLTEXT。
 * <p>
 * 若把不可用也降级成空列表，调用方无法区分「真的没搜到」与「引擎挂了」，
 * 用户会看到静默的空结果，排查时也无从下手。
 *
 * @author shop
 */
public class KeywordSearchUnavailableException extends RuntimeException {

    public KeywordSearchUnavailableException(String message) {
        super(message);
    }

    public KeywordSearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
