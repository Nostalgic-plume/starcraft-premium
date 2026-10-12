package com.shop.rec;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 推荐服务启动类
 *
 * <p>
 * 推荐服务与商城后端是同一工程内的两个独立进程：
 * <ul>
 *     <li><b>商城后端</b>：{@link com.shop.ShopApplication}，端口 8080，承载商城业务与
 *         M-01 行为采集，对外承接前端请求，并代理转发榜单查询；</li>
 *     <li><b>推荐服务</b>：本类，端口 8082，承载 M-03~M-10（画像、商品热度与索引、
 *         双路召回、融合过滤、重排、查询编排、策略配置、可观测），仅绑定回环地址，
 *         只接受商城后端与运营管理后台的调用。</li>
 * </ul>
 *
 * <p>
 * <b>组件隔离约定</b>：本启动类只扫描 {@code com.shop.rec} 包，不加载商城业务组件；
 * 同时固定激活 {@code rec} profile，推荐服务侧组件（含 M-03 画像、M-04 索引刷新等定时任务）
 * 统一标注 {@code @Profile("rec")}，避免商城后端进程重复执行这些任务。
 * 行为采集组件（M-01）处于商城后端侧，标注 {@code @Profile("!rec")}。
 *
 * <p>
 * <b>启动方式</b>：{@code java -jar shop-1.0.0-rec.jar}（由 spring-boot-maven-plugin
 * 的 repackage-rec execution 以 rec classifier 产出）。
 *
 * @author shop
 */
@SpringBootApplication(scanBasePackages = { "com.shop.rec", "com.shop.web" })
@EnableScheduling
public class RecApplication {

    /**
     * 推荐服务启动入口
     *
     * <p>强制附加 {@code rec} profile：推荐服务侧组件与配置（application-rec.yml）仅在此 profile 下生效。
     *
     * @param args 启动参数（可覆盖端口等配置）
     */
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(RecApplication.class);
        application.setAdditionalProfiles("rec");
        application.run(args);
    }
}
