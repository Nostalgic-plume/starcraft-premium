package com.shop.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 官方 Java API Client 装配（功能编号 001）
 * <p>
 * 刻意不使用 {@code spring-boot-starter-data-elasticsearch}：
 * 本项目持久层统一走 MyBatis-Plus，向量库也是手写 SDK 装配，引入 Repository 抽象
 * 只会多一层需要理解、且没有消费方的机制。
 * <p>
 * <b>Bean 创建不连接 ES</b>：{@link RestClient} 构造是懒连接，ES 未启动时应用照常启动；
 * 连通性由 {@code EsProductIndexer.ensureIndex()} 在 ApplicationReadyEvent 中探测。
 * 若此处因连接失败而抛异常，会导致整个后端起不来 —— 这是不可接受的降级放大器。
 *
 * @author shop
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EsClientConfig {

    private final RagElasticsearchProperties properties;

    /**
     * 底层 REST 客户端（连接池 + 超时配置）
     * <p>
     * destroyMethod 由 Spring 在容器关闭时调用，避免连接池线程泄漏。
     *
     * @return 指向 {@code rag.elasticsearch.*} 的 REST 客户端
     */
    @Bean(destroyMethod = "close")
    public RestClient esRestClient() {
        return RestClient.builder(new HttpHost(properties.getHost(), properties.getPort(), properties.getScheme()))
                .setRequestConfigCallback(builder -> builder
                        .setConnectTimeout(properties.getConnectTimeoutMs())
                        .setSocketTimeout(properties.getSocketTimeoutMs()))
                .build();
    }

    /**
     * 官方 Elasticsearch 客户端（JSON-P 映射）
     * <p>
     * 客户端版本与服务端不一致时，ES 8.x 会在首次请求返回 product check 失败，
     * 而该错误在启动期不可见（懒连接）—— 故这里只打一行装配日志，真正的健康裁决
     * 交给 {@code EsProductIndexer.ensureIndex()} 的首次探测。
     *
     * @param esRestClient 底层 REST 客户端
     * @return ElasticsearchClient 实例
     */
    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient esRestClient) {
        log.info("Elasticsearch 客户端已装配: {}:{}{}（期望服务端 8.11.4）",
                properties.getScheme(), properties.getHost(), properties.getPort());
        return new ElasticsearchClient(new RestClientTransport(esRestClient, new JacksonJsonpMapper()));
    }
}
