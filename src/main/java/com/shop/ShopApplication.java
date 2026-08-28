package com.shop;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 电商系统启动类
 *
 * @author shop
 * @since 2024-01-01
 */
@SpringBootApplication
@MapperScan("com.shop.mapper")
@EnableScheduling
public class ShopApplication {

    /**
     * 应用启动入口，负责初始化 Spring 容器并启动内嵌 Web 服务器
     *
     * @param args 启动参数（命令行传入，通常为空；可包含 Spring Boot 配置项，如 --server.port=8080）
     */
    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
