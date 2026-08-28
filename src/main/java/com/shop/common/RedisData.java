package com.shop.common;

import lombok.Data;

/**
 * 逻辑过期缓存包装对象（防缓存击穿）
 *
 * 缓存 value 的 JSON 结构：{@code {"data": <业务数据>, "expireTime": <逻辑过期毫秒时间戳>}}
 * data 存具体业务对象，expireTime 为逻辑过期时间戳；缓存 key 本身物理永不过期，
 * 是否过期由 expireTime 与当前时间比较得出（而非 Redis TTL）。
 *
 * @author shop
 */
@Data
public class RedisData {
    /** 业务数据（单个对象） */
    private Object data;
    /** 逻辑过期毫秒时间戳，null 视为永不过期 */
    private Long expireTime;
}
