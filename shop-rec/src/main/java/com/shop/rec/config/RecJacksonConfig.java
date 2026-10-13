package com.shop.rec.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 推荐服务的 JSON 序列化约定（与商城后端 WebConfig 保持一致）
 *
 * <p>
 * <b>为什么必须显式配置</b>：各表主键统一用雪花算法（{@code IdType.ASSIGN_ID}，19 位），
 * 已超出 JavaScript 的 {@code Number.MAX_SAFE_INTEGER}（2^53-1）。若以 JSON number 下发，
 * 前端 {@code JSON.parse} 会悄悄丢掉末尾精度——榜单里的商品标识一旦失真，
 * 用户点击卡片跳转详情页必然 404，而浏览器控制台不会报任何异常，排查成本极高。
 *
 * <p>
 * 推荐服务是独立进程，商城后端的 Jackson 配置不会随之生效（该进程根本不加载那套 Bean），
 * 故需在此单独声明一份。
 *
 * <p>
 * 时间格式同理：application-rec.yml 中的 {@code spring.jackson.date-format} 只作用于
 * {@code java.util.Date}，对 {@code LocalDateTime} 无效；不显式配置时输出为
 * {@code 2026-10-13T10:30:00}，与商城后端接口的 {@code 2026-10-13 10:30:00} 不一致。
 *
 * @author shop
 */
@Configuration
@Profile("rec")
public class RecJacksonConfig {

    /** 时间展示格式，与商城后端一致 */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 注册长整型与本地时间序列化器
     *
     * @return Jackson 定制器
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer recJacksonCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
            builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
        };
    }
}
