package com.shop.config;

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * @WebMvcTest 测试用的 Mock MyBatis 配置。
 * <p>
 * ShopApplication 上的 {@code @MapperScan("com.shop.mapper")} 会在测试上下文中将全部 17 个
 * Mapper 接口注册为 MapperFactoryBean。这些 Bean 在初始化时需要 SqlSessionFactory 或
 * SqlSessionTemplate，但 @WebMvcTest 排除了 MyBatis 自动配置。
 * <p>
 * 这里不 mock SqlSessionFactory，因为其 setter 方法（setSqlSessionFactory）内部会创建
 * {@code new SqlSessionTemplate(sqlSessionFactory)}，对 mock 对象进行复杂构造。
 * 改为直接 mock SqlSessionTemplate，Spring 通过 setSqlSessionTemplate 注入，仅赋值不构造。
 * </p>
 */
@TestConfiguration
public class MockMyBatisConfig {

    @Bean
    @Primary
    public SqlSessionTemplate mockSqlSessionTemplate() {
        SqlSessionTemplate template = mock(SqlSessionTemplate.class);
        // MapperFactoryBean.checkDaoConfig() 需要非 null Configuration
        Configuration config = mock(Configuration.class);
        given(template.getConfiguration()).willReturn(config);
        given(template.getSqlSessionFactory()).willReturn(mock(SqlSessionFactory.class));
        return template;
    }
}
