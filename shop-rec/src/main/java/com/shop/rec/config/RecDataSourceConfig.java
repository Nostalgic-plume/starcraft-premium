package com.shop.rec.config;

import com.alibaba.druid.pool.DruidDataSource;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * 推荐库数据源配置
 *
 * <p>
 * 推荐子系统使用独立的数据库（shop_rec）与独立数据源，与商城业务库（shop）物理分离：
 * 推荐服务对推荐库读写，对商城业务库只读。本配置类负责：
 * <ol>
 *     <li>注册推荐库数据源（Druid，参数取自 rec.datasource）；</li>
 *     <li>注册独立的 SqlSessionFactory 与 Mapper 扫描路径（com.shop.rec.mapper）；</li>
 *     <li>注册独立事务管理器（recTransactionManager），供画像版本切换等需要事务的场景显式指定。</li>
 * </ol>
 *
 * <p>
 * <b>与商城数据源的关系</b>：商城侧数据源由 Spring Boot 自动配置提供，Mapper 扫描路径为
 * com.shop.mapper（见启动类 @MapperScan），两者互不干扰。推荐库不注册商家数据隔离拦截器
 * （推荐库表无 shop_id 维度），仅注册分页插件。
 *
 * <p>
 * <b>使用方式</b>：推荐模块的写操作若需事务，须显式指定事务管理器：
 * {@code @Transactional(rollbackFor = Exception.class, transactionManager = "recTransactionManager")}。
 *
 * @author shop
 */
@Configuration
@MapperScan(basePackages = "com.shop.rec.mapper", sqlSessionFactoryRef = "recSqlSessionFactory")
public class RecDataSourceConfig {

    /**
     * 推荐库数据源
     *
     * <p>连接参数由 {@link RecDataSourceProperties} 从 rec.datasource 前缀绑定后显式装配，
     * 账号密码支持环境变量注入，不落明文。
     *
     * @param properties 推荐库数据源参数
     * @return 推荐库 Druid 数据源
     */
    @Bean(name = "recDataSource")
    public DataSource recDataSource(RecDataSourceProperties properties) {
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
        return dataSource;
    }

    /**
     * 推荐库 SqlSessionFactory
     *
     * <p>
     * 与默认 SqlSessionFactory 的差异：数据源指向推荐库、下划线转驼峰开启、
     * 挂载全局自动填充处理器（MyMetaObjectHandler，用于 createTime/updateTime）、
     * 注册分页插件。推荐库表无 deleted 字段，故不配置逻辑删除规则。
     *
     * @param recDataSource             推荐库数据源
     * @param metaObjectHandlerProvider 自动填充处理器（可选）
     * @return 推荐库 SqlSessionFactory
     * @throws Exception 构建失败时抛出，交由启动流程中断并暴露问题
     */
    @Bean(name = "recSqlSessionFactory")
    public SqlSessionFactory recSqlSessionFactory(@Qualifier("recDataSource") DataSource recDataSource,
                                                  ObjectProvider<MetaObjectHandler> metaObjectHandlerProvider) throws Exception {
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(recDataSource);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);

        GlobalConfig globalConfig = new GlobalConfig();
        // 自动填充处理器按可选处理：推荐服务是独立进程，只扫描 com.shop.rec 包，不加载商城
        // config 包下的 MyMetaObjectHandler，此时不做自动填充，由推荐库表字段的默认值兜底；
        // 商城后端进程内（M-01 写行为明细）则能正常注入并生效。
        MetaObjectHandler metaObjectHandler = metaObjectHandlerProvider.getIfAvailable();
        if (metaObjectHandler != null) {
            globalConfig.setMetaObjectHandler(metaObjectHandler);
        }
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setIdType(IdType.ASSIGN_ID);
        globalConfig.setDbConfig(dbConfig);
        factoryBean.setGlobalConfig(globalConfig);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        factoryBean.setPlugins(interceptor);

        return factoryBean.getObject();
    }

    /**
     * 推荐库事务管理器
     * 供画像版本有效性切换、配置落库等需要跨表或先查后写的推荐库操作显式指定
     *
     * @param recDataSource 推荐库数据源
     * @return 推荐库事务管理器
     */
    @Bean(name = "recTransactionManager")
    public DataSourceTransactionManager recTransactionManager(@Qualifier("recDataSource") DataSource recDataSource) {
        return new DataSourceTransactionManager(recDataSource);
    }
}
