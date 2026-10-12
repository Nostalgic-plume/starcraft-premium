package com.shop.rec.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推荐库数据源参数
 *
 * <p>
 * 绑定 rec.datasource 前缀。之所以不直接把 {@code @ConfigurationProperties} 挂在
 * DruidDataSource 上，是因为 Spring Boot 3 下第三方数据源类需由 Druid 官方的
 * DruidDataSourceWrapper 承载绑定，直接绑定普通实例会静默失败（表现为 jdbcUrl 为 null）。
 * 此处改为绑定到本 POJO 再显式装配数据源，行为可控且参数一目了然。
 *
 * @author shop
 */
@Data
@Component
@ConfigurationProperties(prefix = "rec.datasource")
public class RecDataSourceProperties {

    /** 推荐库 JDBC 连接串 */
    private String url;

    /** 推荐库账号 */
    private String username;

    /** 推荐库密码 */
    private String password;

    /** JDBC 驱动类名 */
    private String driverClassName = "com.mysql.cj.jdbc.Driver";

    /** 启动预建连接数 */
    private Integer initialSize = 2;

    /** 最小空闲连接数 */
    private Integer minIdle = 2;

    /** 最大连接数 */
    private Integer maxActive = 10;

    /** 借连接最长等待毫秒数 */
    private Long maxWait = 60000L;

    /** 连接保活检测语句 */
    private String validationQuery = "SELECT 1";

    /** 空闲时检测连接可用性 */
    private Boolean testWhileIdle = true;

    /** 借出时检测连接可用性（有额外开销，默认关闭） */
    private Boolean testOnBorrow = false;

    /** 归还时检测连接可用性（有额外开销，默认关闭） */
    private Boolean testOnReturn = false;
}
