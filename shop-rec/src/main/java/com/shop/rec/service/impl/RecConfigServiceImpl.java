package com.shop.rec.service.impl;

import com.shop.rec.common.RecRedis;
import com.shop.rec.constant.RecConfigKeys;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.mapper.RecConfigMapper;
import com.shop.rec.model.entity.RecConfigDO;
import com.shop.rec.service.RecConfigService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 推荐策略配置读取服务实现
 *
 * <p>
 * 加载策略：本服务在进程内维护配置本地缓存，缓存内容来自权威来源 t_rec_config；
 * 每 30 秒比对内存库中的配置版本号（rec:cfg:version），发现变化即全量重载并
 * 同步刷新内存库副本（副本供 Flink 作业等其它进程读取）。
 *
 * <p>
 * 降级策略：内存库或数据库不可读时保持现有缓存继续服务；缓存中缺失的键回退到
 * {@link RecConfigKeys#DEFAULTS} 内置默认值，任何情况下不因配置问题中断推荐链路。
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecConfigServiceImpl implements RecConfigService {

    /** 首次启动时初始化配置版本号 */
    private static final long INITIAL_VERSION = 1L;

    /** 配置版本比对间隔（毫秒），对应详细设计「默认 30 秒内生效」 */
    private static final long REFRESH_INTERVAL_MS = 30_000L;

    private final RecConfigMapper recConfigMapper;

    private final RecRedis recRedis;

    /** 进程内配置缓存：配置键 → 配置值 */
    private final Map<String, String> localCache = new ConcurrentHashMap<>();

    /** 本地已加载的配置版本号，-1 表示尚未加载 */
    private volatile long localVersion = -1L;

    /**
     * 启动时执行一次配置加载
     * 加载失败仅记录日志，由内置默认值保证服务可用，不阻塞应用启动
     */
    @PostConstruct
    public void init() {
        refreshIfChanged();
    }

    /**
     * 比对配置版本号并按需重载
     *
     * <p>每 30 秒执行一次；内存库不可读时保持现有缓存不变，记录告警后等待下个周期重试。
     */
    @Override
    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS)
    public void refreshIfChanged() {
        try {
            long remoteVersion = readRemoteVersion();
            if (remoteVersion == localVersion) {
                return;
            }
            reload(remoteVersion);
        } catch (Exception e) {
            log.warn("配置刷新失败，沿用当前缓存（已加载 {} 项，版本 {}）", localCache.size(), localVersion, e);
        }
    }

    @Override
    public String getString(String key) {
        String value = localCache.get(key);
        return value != null ? value : RecConfigKeys.DEFAULTS.get(key);
    }

    @Override
    public int getInt(String key) {
        return parseInt(getString(key));
    }

    @Override
    public long getLong(String key) {
        return parseLong(getString(key));
    }

    @Override
    public double getDouble(String key) {
        return parseDouble(getString(key));
    }

    @Override
    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(getString(key));
    }

    /**
     * 读取内存库中的配置版本号
     * 版本号键不存在时视为首次启动，初始化为 1
     *
     * @return 当前配置版本号
     */
    private long readRemoteVersion() {
        String value = recRedis.get(RecRedisKeys.CFG_VERSION);
        if (value == null) {
            recRedis.set(RecRedisKeys.CFG_VERSION, String.valueOf(INITIAL_VERSION));
            return INITIAL_VERSION;
        }
        return parseLong(value);
    }

    /**
     * 从权威来源全量重载配置并同步内存库副本
     *
     * @param remoteVersion 本次加载对应的配置版本号
     */
    private void reload(long remoteVersion) {
        List<RecConfigDO> configList = recConfigMapper.selectList(null);

        Map<String, String> loaded = new HashMap<>(configList.size());
        for (RecConfigDO config : configList) {
            loaded.put(config.getConfigKey(), config.getConfigValue());
            recRedis.set(RecRedisKeys.cfg(config.getConfigKey()), config.getConfigValue());
        }

        localCache.clear();
        localCache.putAll(loaded);
        localVersion = remoteVersion;
        log.info("推荐配置已重载, version: {}, 条目数: {}", remoteVersion, loaded.size());
    }

    private int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private double parseDouble(String value) {
        try {
            return value == null ? 0D : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0D;
        }
    }
}
