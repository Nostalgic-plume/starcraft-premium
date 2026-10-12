package com.shop.config;

import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Web 配置
 * 前后端分离部署，静态资源由 Nginx 托管
 * 后端仅保留 Swagger UI 所需的 WebJars 资源映射
 * 关闭默认静态资源映射避免 API 路由被错误解析为静态资源路径
 *
 * @author shop
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 配置静态资源映射：仅保留 Swagger UI 所需的 WebJars 资源路径
     * <p>前端静态资源由 Nginx 托管，后端不再映射其他静态目录。</p>
     *
     * @param registry 资源处理器注册表，用于注册 URL 路径到 classpath 资源的映射
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Swagger UI 资源（来自 springdoc-openapi 的 WebJars）
        registry.addResourceHandler("/swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/swagger-ui/")
                .resourceChain(false);
    }

    /**
     * 配置视图跳转：访问后端根路径或 favicon 时重定向到 Swagger 文档页
     *
     * @param registry 视图控制器注册表，用于注册无 Controller 的路径重定向
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 直接访问后端根路径时跳转到 Swagger 文档页
        registry.addRedirectViewController("/", "/api-docs");
        registry.addRedirectViewController("/favicon.ico", "/api-docs");
    }

    /**
     * CORS 跨域配置
     * 允许 Vue 开发服务器（localhost:3000/3001/3002）跨域访问后端 API
     * 生产环境由 Nginx 统一处理，此配置仅在开发阶段生效
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    /**
     * 全局 Jackson 配置：
     * 1. 所有 Long 类型序列化为字符串（解决雪花算法 ID 精度丢失）
     * 2. LocalDateTime 格式化为 yyyy-MM-dd HH:mm:ss（去除 T、时区、毫秒）
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
            builder.serializerByType(LocalDateTime.class,
                    new LocalDateTimeSerializer(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        };
    }
}
