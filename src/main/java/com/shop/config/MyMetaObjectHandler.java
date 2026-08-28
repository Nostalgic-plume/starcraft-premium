package com.shop.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 自动填充处理器
 * 自动为实体类的 createTime 和 updateTime 字段赋值
 * insert 时两个字段都填当前时间，update 时仅更新 updateTime
 *
 * @author shop
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入时自动填充：为 createTime 与 updateTime 均填入当前系统时间
     *
     * @param metaObject MyBatis 元对象，封装待插入的实体字段信息
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
    }

    /**
     * 更新时自动填充：仅更新 updateTime 为当前系统时间（createTime 保持不变）
     *
     * @param metaObject MyBatis 元对象，封装待更新的实体字段信息
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
