package com.shop.recproxy;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 推荐服务调用参数（商城后端侧）
 *
 * <p>
 * 商城后端作为推荐服务的唯一调用方，需要知道推荐服务地址与内部调用凭证。
 * 凭证通过环境变量注入，配置文件中只留占位符。
 *
 * @author shop
 */
@Data
@Component
@ConfigurationProperties(prefix = "rec.proxy")
public class RecProxyProperties {

    /** 推荐服务基础地址（单机部署下为本机 8082） */
    private String baseUrl = "http://127.0.0.1:8082";

    /**
     * 内部调用凭证
     *
     * <p>与推荐服务侧 {@code rec.internal-token} 必须一致。未配置时推荐服务会拒绝内部调用，
     * 商城后端将走原生热销榜兜底。
     */
    private String internalToken;

    /** 调用推荐服务的超时（毫秒）：比推荐服务自身的 800ms 预算略宽，留出网络与序列化开销 */
    private int timeoutMillis = 1000;
}
