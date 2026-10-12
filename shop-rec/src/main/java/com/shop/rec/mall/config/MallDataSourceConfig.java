package com.shop.rec.mall.config;

import com.alibaba.druid.pool.DruidDataSource;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * 商城业务库只读数据源配置
 *
 * <p>
 * 推荐侧读取商城数据的通道（商品、规格、类目）。与推荐库数据源
 * （{@link com.shop.rec.config.RecDataSourceConfig}）并存，两者互不影响：
 * 推荐库可读写、商城库只读。
 *
 * <p>
 * <b>只注册数据源与 SqlSessionFactory，不注册事务管理器</b>：本数据源仅用于查询，
 * 不需要事务；若注册会与推荐库事务管理器争抢 {@code @Primary} 语义。
 *
 * <p>
 * <b>Mapper 包独立</b>：使用 {@code com.shop.rec.mall.mapper} 而非 {@code com.shop.rec.mapper}，
 * 避免被推荐库的 MapperScan 捕获而错误地绑定到推荐库数据源。
 *
 * @author shop
 */
@Configuration
@Profile("rec")
@MapperScan(basePackages = "com.shop.rec.mall.mapper", sqlSessionFactoryRef = "mallSqlSessionFactory")
public class MallDataSourceConfig {

    /**
     * 商城业务库只读数据源
     *
     * @param properties 只读数据源参数（rec.mall-datasource）
     * @return Druid 数据源
     */
    @Bean(name = "mallDataSource")
    public DataSource mallDataSource(MallDataSourceProperties properties) {
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setDriverClassName(properties.getDriverClassName());
        dataSource.setInitialSize(properties.getInitialSize());
        dataSource.setMinIdle(properties.getMinIdle());
        dataSource.setMaxActive(properties.getMaxActive());
        dataSource.setMaxWait(properties.getMaxWait());
        dataSource.setValidationQuery(properties.getValidationQuery());
        dataSource.setTestWhileIdle(properties.getTestWhileIdle());
        dataSource.setTestOnBorrow(properties.getTestOnBorrow());
        dataSource.setTestOnReturn(properties.getTestOnReturn());
        // 只读连接：从驱动层面阻止误写商城业务库
        dataSource.setDefaultReadOnly(true);
        return dataSource;
    }

    /**
     * 商城业务库 SqlSessionFactory
     *
     * <p>不需要自动填充处理器（只读，无 insert/update）；下划线转驼峰开启以匹配列名与字段名。
     *
     * @param mallDataSource 商城业务库只读数据源
     * @return SqlSessionFactory
     * @throws Exception 构建失败时抛出
     */
    @Bean(name = "mallSqlSessionFactory")
    public SqlSessionFactory mallSqlSessionFactory(@Qualifier("mallDataSource") DataSource mallDataSource) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(mallDataSource);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);

        return factoryBean.getObject();
    }
}
