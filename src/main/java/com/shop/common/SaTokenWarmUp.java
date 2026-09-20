package com.shop.common;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 三端 StpLogic 启动预热
 * <p>
 * {@link StpUserUtil} / {@link StpShopUtil} / {@link StpAdminUtil} 的单例是「类被首次主动引用」时才构造，
 * 构造时才把自身注册进 SaManager；而 {@code @SaCheckLogin(type = "user")} 的校验直接查 SaManager，
 * 不会触发上述类的初始化。于是冷启动后「第一个」打到带 loginType 鉴权注解的请求会抛
 * {@code SaTokenException: 未能获取对应StpLogic, type=user}，表现为接口返回 500「系统异常」，
 * 直到其它代码路径（如登录）先加载了这些类才恢复正常。
 * </p>
 * <p>
 * 这里在 Bean 初始化阶段主动触发三个工具类的类初始化：该阶段早于内嵌 Tomcat 开始接收请求，
 * 因此可堵掉上述竞态。
 * </p>
 *
 * @author shop
 */
@Slf4j
@Component
public class SaTokenWarmUp {

    /** 预热：仅读取 loginType，目的在触发类初始化并写入启动日志，便于排查 */
    @PostConstruct
    public void warmUp() {
        log.info("Sa-Token 三端 StpLogic 预热完成: user={}, shop={}, admin={}",
                StpUserUtil.INSTANCE.getLoginType(),
                StpShopUtil.INSTANCE.getLoginType(),
                StpAdminUtil.INSTANCE.getLoginType());
    }
}
