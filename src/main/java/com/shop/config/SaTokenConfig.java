package com.shop.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 注解鉴权注册
 *
 * <p>sa-token-spring-boot3-starter 的自动装配只注册 Context/Cors/Firewall 三个 filter，
 * 不会注册 SaInterceptor，导致 @SaCheckLogin / @SaCheckPermission 注解全部失效。
 * 这里手动注册拦截器，使注解鉴权真正生效。
 *
 * <p>isAnnotation=true（默认）：仅对标注 @SaCheckXxx 的方法生效，公开接口直接放行。
 *
 * @author shop
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * 注册 Sa-Token 注解鉴权拦截器，使 @SaCheckLogin / @SaCheckPermission / @SaCheckRole 生效
     *
     * @param registry 拦截器注册表，用于向 Spring MVC 注册拦截器及匹配路径
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor()).addPathPatterns("/**");
    }
}
