package com.shop.config;

import com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceWrapper;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.autoconfigure.SpringBootVFS;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * 主数据源（商城业务库）三元组显式声明：数据源 + SqlSessionFactory + 事务管理器
 *
 * <p>
 * <b>为什么需要本类</b>：Spring Boot / MyBatis-Plus / Druid 的相关自动配置普遍带有
 * {@code @ConditionalOnMissingBean} 条件。推荐子系统引入独立数据源后，
 * {@link com.shop.rec.config.RecDataSourceConfig} 注册的 recDataSource、recSqlSessionFactory、
 * recTransactionManager 三个 Bean 会分别抑制下列自动配置：
 * <ol>
 *     <li>Druid starter 的主数据源 → 容器内只剩推荐库数据源，商城 Mapper 连错库
 *         （表现为 {@code Table 'shop_rec.product' doesn't exist}）；</li>
 *     <li>MyBatis-Plus 的主 SqlSessionFactory → 商城 Mapper 落到推荐库的 SqlSessionFactory；</li>
 *     <li>主事务管理器 → {@code @Transactional} 默认按类型注入，会拿到推荐库的事务管理器，
 *         导致商城业务写操作的事务边界与数据源错配。</li>
 * </ol>
 * 因此三处都必须显式声明并标记 {@code @Primary}，让商城侧继续绑定商城业务库。
 *
 * <p>
 * <b>配置来源不变</b>：数据源参数仍取自 spring.datasource.*（DruidDataSourceWrapper 自带装配），
 * MyBatis-Plus 配置仍取自 mybatis-plus.*（MybatisPlusProperties），拦截器仍为
 * {@link MyBatisPlusConfig} 中注册的插件链（商家数据隔离 + 分页）。本类不改变任何既有取值。
 *
 * @author shop
 */
@Configuration
public class PrimaryDataSourceConfig {

    /**
     * 商城业务库数据源（主数据源）
     *
     * <p>连接参数由 DruidDataSourceWrapper 自动绑定 spring.datasource.*，
     * 与引入推荐库之前的取值完全一致；Druid 连接池参数与监控能力仍由 Druid starter 装配。
     *
     * @return 商城业务库 Druid 数据源
     */
    @Primary
    @Bean(name = "dataSource")
    public DataSource dataSource() {
        return new DruidDataSourceWrapper();
    }

    /**
     * 商城业务库 SqlSessionFactory（主）
     *
     * <p>装配内容对齐 MyBatis-Plus 自动配置：配置项取自 mybatis-plus.*，
     * 拦截器取容器内全部 {@link Interceptor}（含商家数据隔离与分页插件），
     * 自动填充处理器挂载现有的 MyMetaObjectHandler。
     *
     * @param dataSource                商城业务库数据源
     * @param properties                MyBatis-Plus 配置（mybatis-plus.*）
     * @param metaObjectHandlerProvider 自动填充处理器（可选）
     * @param interceptors              容器内全部 MyBatis 拦截器
     * @return 商城业务库 SqlSessionFactory
     * @throws Exception 构建失败时抛出，交由启动流程中断并暴露问题
     */
    @Primary
    @Bean(name = "sqlSessionFactory")
    public SqlSessionFactory sqlSessionFactory(@Qualifier("dataSource") DataSource dataSource,
                                               MybatisPlusProperties properties,
                                               ObjectProvider<MetaObjectHandler> metaObjectHandlerProvider,
                                               Interceptor[] interceptors) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setVfs(SpringBootVFS.class);

        MybatisConfiguration configuration = new MybatisConfiguration();
        MybatisPlusProperties.CoreConfiguration coreConfiguration = properties.getConfiguration();
        if (coreConfiguration != null) {
            coreConfiguration.applyTo(configuration);
        }
        factoryBean.setConfiguration(configuration);

        GlobalConfig globalConfig = properties.getGlobalConfig();
        if (globalConfig == null) {
            globalConfig = new GlobalConfig();
        }
        MetaObjectHandler metaObjectHandler = metaObjectHandlerProvider.getIfAvailable();
        if (metaObjectHandler != null) {
            globalConfig.setMetaObjectHandler(metaObjectHandler);
        }
        factoryBean.setGlobalConfig(globalConfig);

        if (StringUtils.hasLength(properties.getTypeAliasesPackage())) {
            factoryBean.setTypeAliasesPackage(properties.getTypeAliasesPackage());
        }
        Resource[] mapperLocations = properties.resolveMapperLocations();
        if (mapperLocations != null && mapperLocations.length > 0) {
            factoryBean.setMapperLocations(mapperLocations);
        }
        if (interceptors != null && interceptors.length > 0) {
            factoryBean.setPlugins(interceptors);
        }

        return factoryBean.getObject();
    }

    /**
     * 商城业务库事务管理器（主）
     *
     * <p>供商城侧未显式指定事务管理器的 {@code @Transactional} 使用；
     * 推荐库的事务须显式指定 {@code transactionManager = "recTransactionManager"}。
     *
     * @param dataSource 商城业务库数据源
     * @return 商城业务库事务管理器
     */
    @Primary
    @Bean(name = "transactionManager")
    public DataSourceTransactionManager transactionManager(@Qualifier("dataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
