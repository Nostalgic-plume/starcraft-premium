package com.shop.common;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 逻辑过期缓存工具（防缓存击穿）
 *
 * 背景：缓存击穿 = 热点 key（如爆款商品详情）在缓存过期的瞬间，大量并发请求同时 miss 缓存、
 * 全部回源 DB，DB 被瞬时压垮。它区别于「穿透」（查不存在的 id，由布隆过滤器前置拦截）和
 * 「雪崩」（大量 key 同时过期，由 TTL 随机化缓解）。
 *
 * 逻辑过期方案（区别于"物理 TTL 过期"）：
 * <ol>
 *   <li>缓存 key 物理永不过期，value 存 {@link RedisData}：业务数据 data + 逻辑过期时间戳 expireTime</li>
 *   <li>读命中且未过期 → 直接返回；读命中但逻辑过期 → <b>返回旧数据</b>（保证可用性），
 *       同时尝试获取互斥锁，拿到锁的唯一线程去 DB 重建，其余线程直接返回旧数据——
 *       过期瞬间没有任何请求打到 DB</li>
 *   <li>缓存不存在（首次访问 / 被主动删除）→ 加锁后同步重建，此时必须返回新鲜数据</li>
 * </ol>
 *
 * 适用：单个对象缓存的读多写少场景（商品详情）。集合缓存如需逻辑过期可另加方法。
 *
 * 一致性说明：逻辑过期缓存依赖"主动删除"保证变更立即可见（商品修改/删除时 delete key），
 * 删除后下一次读走「缓存不存在 → 同步重建」分支，拿到的是最新数据。
 *
 * 互斥锁用 Redis SETNX（自带 TTL 兜底防死锁），天然兼容未来多实例部署。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCache {

    private final RedisTemplate<String, String> redisTemplate;

    /** 互斥锁 key 前缀（逻辑过期重建互斥，防止热点 key 击穿 DB） */
    private static final String LOCK_KEY_PREFIX = "lock:";
    /** 锁自动过期时间：重建是一次 DB 查询 + 写缓存，毫秒级完成，10s 足够且兜底防死锁 */
    private static final long LOCK_TTL_SECONDS = 10;
    /** 缓存不存在分支加锁失败后的自旋间隔与上限 */
    private static final long RETRY_WAIT_MS = 30;
    private static final int RETRY_TIMES = 50;

    /**
     * 异步重建线程池：核心 1、最大 2、队列 100；重建只是"续命"，用户已在读旧数据，
     * 因此队列满直接丢弃（避免重建任务积压占内存），重建失败时下次读再重建
     */
    private final ExecutorService rebuildExecutor = new ThreadPoolExecutor(
            1, 2, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            namedThreadFactory("cache-rebuild"),
            new ThreadPoolExecutor.DiscardPolicy());

    /**
     * 写入逻辑过期缓存（物理永不过期）
     *
     * @param key        缓存 key
     * @param value      业务数据（单个对象）
     * @param ttlSeconds 逻辑过期时长（秒）
     */
    public void setWithLogicalExpire(String key, Object value, long ttlSeconds) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(System.currentTimeMillis() + ttlSeconds * 1000);
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 逻辑过期读：防缓存击穿的核心入口
     *
     * @param key        缓存 key
     * @param clazz      业务数据类型
     * @param ttlSeconds 逻辑过期时长（秒），与写入时一致
     * @param dbLoader   DB 查询回调（缓存过期 / 不存在时回源）
     * @return 业务数据
     */
    public <T> T queryWithLogicalExpire(String key, Class<T> clazz, long ttlSeconds, Supplier<T> dbLoader) {
        // ===== 分支一：缓存不存在（首次访问 / 被主动删除）=====
        String cached = redisTemplate.opsForValue().get(key);
        if (cached == null) {
            if (tryLock(key)) {
                try {
                    // 双检：等待锁期间可能已被其他线程重建
                    String afterLock = redisTemplate.opsForValue().get(key);
                    if (afterLock != null) {
                        return parseData(afterLock, clazz);
                    }
                    T data = dbLoader.get();                 // 同步重建，必须拿新鲜数据
                    setWithLogicalExpire(key, data, ttlSeconds);
                    return data;
                } finally {
                    unlock(key);
                }
            }
            // 加锁失败：别的线程正在重建，自旋等待其写入
            return spinAndReRead(key, clazz, dbLoader);
        }

        // ===== 分支二：缓存命中 =====
        RedisData redisData = JSONUtil.toBean(cached, RedisData.class);
        Long expireTime = redisData.getExpireTime();
        if (expireTime == null || expireTime > System.currentTimeMillis()) {
            // 未过期：直接返回
            return parseData(redisData.getData(), clazz);
        }

        // 逻辑过期：返回旧数据 + 尝试加锁异步重建（防击穿核心）
        if (tryLock(key)) {
            try {
                String afterLock = redisTemplate.opsForValue().get(key);
                if (afterLock != null) {
                    RedisData rd = JSONUtil.toBean(afterLock, RedisData.class);
                    if (rd.getExpireTime() != null && rd.getExpireTime() > System.currentTimeMillis()) {
                        return parseData(rd.getData(), clazz);   // 已被其他线程重建，返回新数据
                    }
                }
                T oldData = parseData(redisData.getData(), clazz);  // 先取旧数据（下面返回用）
                rebuildExecutor.execute(() -> {
                    try {
                        setWithLogicalExpire(key, dbLoader.get(), ttlSeconds);
                        log.debug("逻辑过期缓存异步重建完成, key={}", key);
                    } catch (Exception e) {
                        // 重建失败不阻塞主流程：旧数据照常返回，下次读再重建
                        log.warn("逻辑过期缓存异步重建失败, key={}, 原因: {}", key, e.getMessage());
                    }
                });
                return oldData;
            } finally {
                unlock(key);
            }
        }
        // 加锁失败：说明已有线程在重建，直接返回旧数据
        return parseData(redisData.getData(), clazz);
    }

    /** 删除缓存（商品变更时主动失效，触发下次「缓存不存在 → 同步重建」） */
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    /** 缓存不存在分支：加锁失败后自旋等待重建线程写入，超时兜底同步查 DB */
    private <T> T spinAndReRead(String key, Class<T> clazz, Supplier<T> dbLoader) {
        for (int i = 0; i < RETRY_TIMES; i++) {
            try {
                Thread.sleep(RETRY_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            String after = redisTemplate.opsForValue().get(key);
            if (after != null) {
                return parseData(after, clazz);
            }
        }
        // 超时兜底：允许重复查 DB，保证正确性优先于极致并发
        return dbLoader.get();
    }

    /** 尝试获取互斥锁（Redis SETNX，兼容多实例部署） */
    private boolean tryLock(String key) {
        String lockKey = LOCK_KEY_PREFIX + key;
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS));
    }

    /** 释放互斥锁（delete 即可，锁本身带 TTL 兜底防死锁） */
    private void unlock(String key) {
        redisTemplate.delete(LOCK_KEY_PREFIX + key);
    }

    /** 解析缓存为业务对象：data 已是目标类型则原样返回，否则按 clazz 反序列化 */
    @SuppressWarnings("unchecked")
    private <T> T parseData(Object data, Class<T> clazz) {
        if (data == null) {
            return null;
        }
        if (clazz.isInstance(data)) {
            return (T) data;
        }
        // hutool 没有 toBean(Object, Class) 重载：RedisData.data 反序列化后是嵌套 JSONObject，
        // 先序列化回 JSON 字符串，再走 toBean(String, Class) 重载转目标类型
        return JSONUtil.toBean(JSONUtil.toJsonStr(data), clazz);
    }

    private ThreadFactory namedThreadFactory(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix);
            t.setDaemon(true);
            return t;
        };
    }
}
