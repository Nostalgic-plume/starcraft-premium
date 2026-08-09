package com.shop.config;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.shop.mapper.ProductMapper;
import com.shop.model.entity.ProductDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 商品布隆过滤器配置（防缓存穿透）
 *
 * 原理：缓存穿透 = 大量请求查询不存在的商品 id，每次都绕过缓存直接打到 DB。
 * 布隆过滤器可 100% 判定「id 不存在」（无假阴性），命中"可能存在"才放行到缓存/DB，
 * 从而把不存在 id 的请求在第一层直接拦截掉。
 *
 * 设计要点：
 * <ol>
 *   <li>商品 id 只增不删，无需考虑删除时移除（Guava 布隆也不支持移除）。逻辑删除/下架的商品
 *       id 仍留在过滤器里（mightContain=true），只会多走一次缓存 miss → DB 查询，无害。</li>
 *   <li>预估上限 10 万、误判率 1%，内存占用约 120KB，可忽略。</li>
 *   <li>误判（假阳性）只会让不存在的 id 多查一次 DB，最终仍抛「商品不存在」，不影响正确性。</li>
 *   <li>多实例部署时各实例过滤器独立：新商品只写入"新增时所在实例"的本地过滤器。单机/演示环境
 *       无碍；如需多实例一致，可改用 Redis 版布隆（如 Redisson RBloomFilter）。</li>
 * </ol>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class BloomFilterConfig {

    private final ProductMapper productMapper;

    /**
     * 注册商品 ID 布隆过滤器，容器启动时全量加载 product.id（逻辑删除的自动被 MyBatis-Plus 过滤）
     */
    @Bean
    public BloomFilter<Long> productBloomFilter() {
        // 预估商品上限 10 万、误判率 1%——商品 id 只增不删，给足余量
        BloomFilter<Long> filter = BloomFilter.create(Funnels.longFunnel(), 100_000, 0.01);
        List<Long> ids = productMapper.selectList(null).stream()
                .map(ProductDO::getId)
                .toList();
        ids.forEach(filter::put);
        log.info("商品布隆过滤器初始化完成，已加载商品 ID {} 个", ids.size());
        return filter;
    }
}
