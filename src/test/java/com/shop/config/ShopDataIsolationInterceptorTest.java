package com.shop.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.shop.common.SaTokenMockUtil;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ShopDataIsolationInterceptor 单元测试
 *
 * <p>重点回归：MyBatis-Plus 分页插件写回分页参数时，会把 BoundSql 的 parameterMappings
 * 换成 {@code Collections.unmodifiableList}。此时若直接 {@code add()} 新参数会抛
 * {@code UnsupportedOperationException}，导致商家会话下的分页查询整体 500。
 *
 * @author shop
 */
@DisplayName("ShopDataIsolationInterceptor - 商家数据隔离拦截器")
class ShopDataIsolationInterceptorTest {

    private final ShopDataIsolationInterceptor interceptor = new ShopDataIsolationInterceptor();

    @Test
    @DisplayName("参数集合不可变时：仍能注入 shop_id 且不抛异常（MP 分页 BoundSql 场景）")
    void beforeQuery_ShouldInjectShopId_WhenParameterMappingsUnmodifiable() {
        Configuration configuration = new MybatisConfiguration();
        BoundSql boundSql = newBoundSql(configuration,
                "SELECT * FROM product WHERE deleted = 0",
                // 复刻 MyBatis-Plus 分页插件的产物：不可变集合
                Collections.unmodifiableList(new ArrayList<>()));
        MappedStatement ms = mockStatement(configuration);

        try (var scope = SaTokenMockUtil.userLogin(50L)) {
            assertDoesNotThrow(() ->
                            interceptor.beforeQuery(mock(Executor.class), ms, null, RowBounds.DEFAULT, null, boundSql),
                    "不可变参数集合不应导致 UnsupportedOperationException");
        }

        assertTrue(boundSql.getSql().contains("shop_id"), "SQL 应追加 shop_id 条件");
        assertTrue(boundSql.getParameterMappings().stream()
                        .anyMatch(m -> "_shopId".equals(m.getProperty())),
                "应新增 _shopId 参数映射");
        assertEquals(50L, boundSql.getAdditionalParameter("_shopId"), "shop_id 取值应为当前商家");
    }

    @Test
    @DisplayName("参数集合可变时：同样正确注入（保持既有行为）")
    void beforeQuery_ShouldInjectShopId_WhenParameterMappingsMutable() {
        Configuration configuration = new MybatisConfiguration();
        BoundSql boundSql = newBoundSql(configuration,
                "SELECT * FROM orders",
                new ArrayList<>(List.of()));
        MappedStatement ms = mockStatement(configuration);

        try (var scope = SaTokenMockUtil.userLogin(77L)) {
            assertDoesNotThrow(() ->
                    interceptor.beforeQuery(mock(Executor.class), ms, null, RowBounds.DEFAULT, null, boundSql));
        }

        assertEquals(77L, boundSql.getAdditionalParameter("_shopId"));
    }

    @Test
    @DisplayName("重复拦截同一 BoundSql：不重复注入 shop_id 参数")
    void beforeQuery_ShouldNotDuplicateParameter_WhenInvokedTwice() throws Exception {
        Configuration configuration = new MybatisConfiguration();
        BoundSql boundSql = newBoundSql(configuration,
                "SELECT * FROM product",
                Collections.unmodifiableList(new ArrayList<>()));
        MappedStatement ms = mockStatement(configuration);

        try (var scope = SaTokenMockUtil.userLogin(50L)) {
            interceptor.beforeQuery(mock(Executor.class), ms, null, RowBounds.DEFAULT, null, boundSql);
            assertDoesNotThrow(() ->
                    interceptor.beforeQuery(mock(Executor.class), ms, null, RowBounds.DEFAULT, null, boundSql));
        }

        long count = boundSql.getParameterMappings().stream()
                .filter(m -> "_shopId".equals(m.getProperty()))
                .count();
        assertEquals(1, count, "重复拦截后 _shopId 参数仍应只有一个");
    }

    /** 构造一条 SELECT 的 MappedStatement mock */
    private MappedStatement mockStatement(Configuration configuration) {
        MappedStatement ms = mock(MappedStatement.class);
        when(ms.getSqlCommandType()).thenReturn(SqlCommandType.SELECT);
        when(ms.getId()).thenReturn("com.shop.mapper.ProductMapper.selectPage");
        when(ms.getConfiguration()).thenReturn(configuration);
        return ms;
    }

    private BoundSql newBoundSql(Configuration configuration, String sql, List<ParameterMapping> mappings) {
        return new BoundSql(configuration, sql, mappings, null);
    }
}
