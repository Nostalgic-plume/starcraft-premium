package com.shop.rag.search;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 纯 Mockito 测试的 MyBatis-Plus 初始化支持
 * <p>
 * {@code LambdaQueryWrapper} 在**构建时**（而非执行时）就要解析实体 lambda 并查 {@code TableInfo}
 * 缓存；该缓存平时由 MyBatis-Plus 在启动装配 Mapper 的过程中写入。纯单测没有 Spring 容器，
 * 拿不到缓存，会在 {@code wrapper.select(XxxDO::getYyy)} 处直接抛
 * {@code MybatisPlusException: can not find lambda cache for this entity} ——
 * 表现为「mock 的 selectList 从未被调用、方法返回空集合」，容易误判成 mock 写错了。
 * <p>
 * 这里在测试启动前手动为所需实体建一次缓存，让不依赖数据库的单元测试也能构造 Wrapper。
 *
 * @author shop
 */
final class MyBatisPlusTestSupport {

    private MyBatisPlusTestSupport() {
    }

    /**
     * 为给定实体初始化 TableInfo 缓存（幂等）
     *
     * @param entityClasses 需要参与 Lambda 解析的实体类
     */
    static void initTableInfo(Class<?>... entityClasses) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entityClass : entityClasses) {
            if (TableInfoHelper.getTableInfo(entityClass) == null) {
                TableInfoHelper.initTableInfo(assistant, entityClass);
            }
        }
    }
}
