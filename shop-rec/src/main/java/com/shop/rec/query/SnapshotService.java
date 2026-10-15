package com.shop.rec.query;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.rec.common.RecRedis;
import com.shop.rec.constant.RecConfigKeys;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.query.model.RankSnapshot;
import com.shop.rec.rank.model.RankedItem;
import com.shop.rec.service.RecConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * 榜单快照读写（M-08）
 *
 * <p>
 * 快照用于保证同一用户翻页顺序一致、不重复曝光。生成公式与复用判定见详细设计 6.5：
 * {@code snapshotId = "u" + userId + "_" + epochSecond + "_" + md5(商品标识序列)[0:6]}，
 * 三段分别表示归属用户（防跨用户读取）、生成时刻、内容摘要。
 *
 * <p>
 * <b>失效与重建</b>：旧快照不主动删除，等有效期自然过期——避免翻页请求在临界时刻同时失效
 * （详细设计 6.5(4)）。降级结果同样写快照，保证降级期间翻页顺序稳定。
 *
 * @author shop
 */
@Slf4j
@Service
@Profile("rec")
@RequiredArgsConstructor
public class SnapshotService {

    /** 快照标识中内容摘要的截取长度 */
    private static final int DIGEST_LENGTH = 6;

    /** 默认快照有效期（秒），配置缺失时兜底 */
    /**
     * 快照兜底有效期（秒）——配置项 {@code cache.result.ttl} 缺失或非法时使用
     *
     * <p>由 300 提到 1800：首页改为无限滚动后，用户的连续滑动会话会显著长于 5 分钟。
     * 快照一旦在会话中途过期，下一次触底续接会走到「未命中快照 → 重算」分支，
     * 表现为<b>已滑过的内容顺序突变、并出现重复卡片</b>。配置项本身的取值见
     * {@code t_rec_config.cache.result.ttl}，两者需保持一致。
     */
    private static final long DEFAULT_TTL_SECONDS = 1800L;

    private final RecRedis recRedis;

    private final ObjectMapper objectMapper;

    private final RecConfigService configService;

    /**
     * 生成快照标识
     *
     * @param userId 用户标识
     * @param items  完整榜单（顺序即最终展示顺序）
     * @return 快照标识
     */
    public String generateSnapshotId(Long userId, java.util.List<RankedItem> items) {
        long epochSecond = Instant.now().getEpochSecond();
        String itemSequence = items.stream()
                .map(item -> String.valueOf(item.getItemId()))
                .collect(Collectors.joining(","));
        String digest = DigestUtil.md5Hex(itemSequence);
        return "u" + userId + "_" + epochSecond + "_" + digest.substring(0, DIGEST_LENGTH);
    }

    /**
     * 写入快照
     *
     * <p>写入失败不抛异常——快照只是加速手段，丢失只会导致下次请求重新计算。
     *
     * @param userId   用户标识
     * @param snapshot 快照
     */
    public void save(Long userId, RankSnapshot snapshot) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            recRedis.setex(RecRedisKeys.result(userId), json, resolveTtlSeconds());
        } catch (Exception e) {
            log.warn("榜单快照写入失败，跳过缓存, userId: {}", userId, e);
        }
    }

    /**
     * 读取快照
     *
     * <p>返回值仅代表「键存在且可解析」；是否可复用还需调用方校验快照标识一致、
     * 未过期（Redis TTL 保证）与页码未超总页数。
     *
     * @param userId 用户标识
     * @return 快照；不存在或解析失败返回 null
     */
    public RankSnapshot read(Long userId) {
        try {
            String json = recRedis.get(RecRedisKeys.result(userId));
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, RankSnapshot.class);
        } catch (Exception e) {
            log.warn("榜单快照读取失败，按未命中处理, userId: {}", userId, e);
            return null;
        }
    }

    /**
     * 解析快照有效期
     *
     * @return 有效期（秒）
     */
    private long resolveTtlSeconds() {
        int ttl = configService.getInt(RecConfigKeys.CACHE_RESULT_TTL);
        return ttl > 0 ? ttl : DEFAULT_TTL_SECONDS;
    }
}
