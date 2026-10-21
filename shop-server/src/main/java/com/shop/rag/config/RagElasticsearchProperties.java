package com.shop.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 关键词路 Elasticsearch 配置（{@code rag.elasticsearch.*}）
 * <p>
 * 关键词路从 MySQL FULLTEXT 升级为 ES + IK 后的可切换开关与连接参数。
 * 设计见 {@code docs/specs/001-es-keyword-search/design.md}。
 * <p>
 * ⚠️ 超时默认值刻意偏短（连接 500ms / 读 1.5s）：ES 是关键词路的**首选**而非唯一路径，
 * 故障时必须快速失败并回退 FULLTEXT，不能让一次 ES 抖动拖慢整条 RAG 检索。
 *
 * @author shop
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.elasticsearch")
public class RagElasticsearchProperties {

    /** 总开关：false 时完全不访问 ES，直接走 MySQL FULLTEXT（用于回滚） */
    private boolean enabled = true;

    /** 协议（compose 为 http，且已关闭 xpack.security） */
    private String scheme = "http";

    /** 主机（compose 端口只绑 127.0.0.1） */
    private String host = "localhost";

    /** 端口 */
    private int port = 9200;

    /** 商品索引名 */
    private String indexName = "shop_products";

    /** 连接超时（ms）：ES 未启动时快速失败 */
    private int connectTimeoutMs = 500;

    /** 读超时（ms） */
    private int socketTimeoutMs = 1500;

    /** 熔断后的半开重试间隔（ms） */
    private long recoveryIntervalMs = 30_000L;

    /** 全量导入时每批 bulk 条数（ES bulk 单批可远大于向量化的 25 条） */
    private int bulkBatchSize = 200;

    /**
     * multi_match 的 minimum_should_match（ES 条件语法），默认**空 = 纯 OR**。
     * <p>
     * ⚠️ 2026-10-21 实测结论（见 evaluation-2026-10-21.md §4.2）：提高门槛会同时误伤正常查询，
     * 37 条评估集上 Hit@3 分别为 OR=0.973、{@code 2<70%}=0.892、{@code 100%}=0.946。
     * 典型误伤是「轻薄本」——IK 切成「轻薄」「本」，门槛要求「本」也命中，而商品名是「轻薄笔记本」。
     * 故默认保持 OR；配置项保留，供后续逐 query 长度调优时复用。
     */
    private String minimumShouldMatch = "";
}
