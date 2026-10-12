package com.shop.rec.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推荐子系统运行参数
 *
 * <p>
 * 绑定 rec.* 前缀的配置项（推荐库数据源参数由 {@link RecDataSourceConfig} 单独绑定 rec.datasource）。
 * 凭证类参数一律通过环境变量注入，配置文件中只保留占位符。
 *
 * @author shop
 */
@Data
@Component
@ConfigurationProperties(prefix = "rec")
public class RecProperties {

    /**
     * 内部调用凭证
     *
     * <p>商城后端调用推荐服务时通过请求头 X-Internal-Token 携带，用于确认调用方身份，
     * 防止绕过商城后端直接访问推荐服务伪造用户身份。未配置时内部调用一律拒绝并告警。
     */
    private String internalToken;

    /**
     * 商城后端基础地址
     *
     * <p>推荐服务在需要回查商城数据（如商品详情补充）时使用；单机部署下指向本机 8080。
     */
    private String shopBaseUrl = "http://127.0.0.1:8080";
}
