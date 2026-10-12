package com.shop.rec.service;

/**
 * 推荐策略配置读取服务
 *
 * <p>
 * 供 M-01~M-10 各模块读取生效中的策略参数。配置的权威来源是推荐库的 t_rec_config，
 * 运行时以内存库副本（rec:cfg:{configKey}）为准，本服务在进程内维护一份本地缓存，
 * 每 30 秒比对配置版本号，发现变化后重新加载（对应详细设计 3.9 步骤 6）。
 *
 * <p>
 * <b>失败语义</b>：配置不可读时回退到 {@link com.shop.rec.constant.RecConfigKeys#DEFAULTS}
 * 中定义的内置默认值，禁止因配置问题中断推荐链路。
 *
 * @author shop
 */
public interface RecConfigService {

    /**
     * 读取字符串配置
     *
     * @param key 配置键，取值见 {@link com.shop.rec.constant.RecConfigKeys}
     * @return 配置值；配置与默认值均缺失时返回 null
     */
    String getString(String key);

    /**
     * 读取整型配置
     *
     * @param key 配置键
     * @return 配置值；解析失败时返回 0
     */
    int getInt(String key);

    /**
     * 读取长整型配置
     *
     * @param key 配置键
     * @return 配置值；解析失败时返回 0
     */
    long getLong(String key);

    /**
     * 读取浮点配置
     *
     * @param key 配置键
     * @return 配置值；解析失败时返回 0
     */
    double getDouble(String key);

    /**
     * 读取布尔配置
     *
     * @param key 配置键
     * @return 取值「true」时返回 true，其余返回 false
     */
    boolean getBoolean(String key);

    /**
     * 比对配置版本号并重载本地缓存
     *
     * <p>由定时任务每 30 秒调用一次；版本号无变化时不产生任何动作。
     * 内存库不可读时保持现有缓存不变并记录告警，不影响已生效配置继续服务。
     */
    void refreshIfChanged();
}
