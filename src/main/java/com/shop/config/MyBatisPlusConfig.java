package com.shop.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置
 * 注册分页插件 + 商家数据隔离拦截器
 *
 * @author shop
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * MyBatis-Plus 插件集合
     * 注册顺序：商家数据隔离（优先执行 shop_id 过滤）→ 分页（基于已过滤的结果分页）
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 商家数据隔离必须在分页之前执行，确保 count 查询和 LIMIT 都基于已过滤的数据
        interceptor.addInnerInterceptor(new ShopDataIsolationInterceptor());
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
