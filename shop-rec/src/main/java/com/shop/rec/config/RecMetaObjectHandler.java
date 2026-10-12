package com.shop.rec.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 推荐库自动填充处理器
 *
 * <p>
 * <b>为什么推荐模块需要自己的一份</b>：实体的 {@code createTime} / {@code updateTime} 标注了
 * {@code @TableField(fill = ...)}，MyBatis-Plus 会<b>强制把这些字段写进 INSERT/UPDATE 语句</b>。
 * 若容器内没有 {@link MetaObjectHandler}，字段值就是 null，插入时会以 null 覆盖表上的
 * {@code DEFAULT CURRENT_TIMESTAMP}，直接报 {@code Column 'create_time' cannot be null}。
 *
 * <p>
 * 商城的 {@code MyMetaObjectHandler} 位于 {@code com.shop.config}，而推荐服务进程只扫描
 * {@code com.shop.rec} 包，加载不到它，因此推荐模块必须自带一份。
 *
 * <p>
 * <b>为什么用 @Profile("rec") 隔离</b>：商城后端进程会加载推荐模块（为启用 M-01 行为上报入口），
 * 若本类不加 profile，容器内会同时存在两个 {@code MetaObjectHandler}，
 * {@code RecDataSourceConfig} 的可选注入（{@code getIfAvailable()}）在多个候选时会抛
 * {@code NoUniqueBeanDefinitionException}。限定 profile 后两个进程各自只有一个填充器。
 *
 * @author shop
 */
@Component
@Profile("rec")
public class RecMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入时填充创建与更新时间
     *
     * @param metaObject MyBatis 元对象
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
    }

    /**
     * 更新时仅填充更新时间
     *
     * @param metaObject MyBatis 元对象
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
