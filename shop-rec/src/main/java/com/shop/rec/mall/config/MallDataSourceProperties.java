package com.shop.rec.mall.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 商城业务库只读数据源参数
 *
 * <p>
 * 绑定 rec.mall-datasource 前缀。推荐服务需要读取商城的商品、规格与类目数据
 * （M-04 维护商品维度与候选索引），按概要设计 3.2 的要求对商城业务库<b>只读</b>：
 * 推荐侧只执行 SELECT，不写入商城任何表。
 *
 * <p>
 * 仅在推荐服务进程加载（{@code @Profile("rec")}）：商城后端进程已有指向商城库的主数据源，
 * 无需重复创建。
 *
 * @author shop
 */
@Data
@Component
@Profile("rec")
@ConfigurationProperties(prefix = "rec.mall-datasource")
public class MallDataSourceProperties {

    /** 商城业务库连接串 */
    private String url;

    /** 只读账号（生产环境应为仅授予 SELECT 权限的账号） */
    private String username;

    /** 密码 */
    private String password;

    /** JDBC 驱动类名 */
    private String driverClassName = "com.mysql.cj.jdbc.Driver";

    /** 启动预建连接数 */
    private Integer initialSize = 2;

    /** 最小空闲连接数 */
    private Integer minIdle = 2;

    /** 最大连接数：推荐侧只有批任务读取，规模收敛 */
    private Integer maxActive = 8;

    /** 借连接最长等待毫秒数 */
    private Long maxWait = 60000L;

    /** 连接保活检测语句 */
    private String validationQuery = "SELECT 1";

    /** 空闲时检测连接可用性 */
    private Boolean testWhileIdle = true;

    /** 借出时检测连接可用性 */
    private Boolean testOnBorrow = false;

    /** 归还时检测连接可用性 */
    private Boolean testOnReturn = false;
}
