package com.shop.rec.collect;

import com.shop.common.BusinessException;
import com.shop.common.StpUserUtil;
import com.shop.mapper.ProductMapper;
import com.shop.rec.common.RecRedis;
import com.shop.rec.constant.BehaviorTypes;
import com.shop.rec.constant.RecConfigKeys;
import com.shop.rec.constant.RecErrorCode;
import com.shop.rec.constant.RecRedisKeys;
import com.shop.rec.model.dto.BehaviorEventDTO;
import com.shop.rec.model.dto.BehaviorReportDTO;
import com.shop.rec.model.entity.UserBehaviorLogDO;
import com.shop.rec.service.RecConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 行为采集编排（M-01）
 *
 * <p>
 * 按《详细设计说明书》3.1 的业务处理步骤编排：身份解析 → 事件时间校验 → 商品存在性校验 →
 * 采集防护（去重/限频/无效/爬虫/采样）→ 投递消息队列 → 异步落库 → 排除清单维护 → 返回受理结果。
 * 接口不等待落库完成，保证 100ms 内返回。
 *
 * <p>
 * <b>进程归属</b>：行为采集内嵌商城后端（M-01），标注 {@code @Profile("!rec")}，
 * 推荐服务进程不加载本类。
 *
 * @author shop
 */
@Slf4j
@Service
@Profile("!rec")
@RequiredArgsConstructor
public class BehaviorCollectService {

    /** 事件时间与服务器时间的允许偏差（分钟），超出即判为越界 */
    private static final long EVENT_TIME_TOLERANCE_MINUTES = 10L;

    private final BehaviorGuard behaviorGuard;

    private final BehaviorEventProducer eventProducer;

    private final BehaviorLogService behaviorLogService;

    private final ProductMapper productMapper;

    private final RecRedis recRedis;

    private final RecConfigService configService;

    /**
     * 受理行为上报
     *
     * @param dto    上报请求（已完成参数校验）
     * @param source 来源标识（请求头，可为空）
     * @return 命中的丢弃原因；受理成功返回 null
     * @throws BusinessException 登录态缺失（1002）、行为时间越界（1004）、商品不存在（1003）
     */
    public CollectReject report(BehaviorReportDTO dto, String source) {
        Long userId = resolveUserId();
        OffsetDateTime eventTime = parseEventTime(dto.getEventTime());
        checkItemExists(dto.getItemId());

        CollectReject reject = behaviorGuard.check(dto, userId, source);
        if (reject != null) {
            recordReject(userId, dto, reject, source);
            return reject;
        }

        BehaviorEventDTO event = buildEvent(dto, userId, eventTime);
        boolean delivered = eventProducer.send(event);
        if (!delivered) {
            // 投递失败降级：事件仍落库，保证画像数据链路完整（详细设计 3.1 异常分支）
            log.warn("行为事件投递失败，降级为落库补偿, userId: {}, eventId: {}", userId, dto.getEventId());
        }

        behaviorLogService.append(buildBehaviorLog(dto, userId, eventTime));
        maintainExcludeList(dto, userId);
        return null;
    }

    /**
     * 身份解析：用户标识只能来自登录态，禁止由请求参数指定
     *
     * @return 用户标识
     * @throws BusinessException 登录态缺失时抛出（错误码 1002，不采集任何数据）
     */
    private Long resolveUserId() {
        if (!StpUserUtil.INSTANCE.isLogin()) {
            throw new BusinessException(RecErrorCode.NOT_LOGIN, "登录态缺失，不采集行为数据");
        }
        return StpUserUtil.INSTANCE.getLoginIdAsLong();
    }

    /**
     * 解析并校验事件时间
     *
     * <p>以行为发生的事件时间为准（不使用到达时间），并校验与服务器时间偏差不超过 10 分钟，
     * 防止异常历史数据污染当前特征窗口。
     *
     * @param rawEventTime 带时区的 ISO 8601 时间字符串
     * @return 解析后的时间
     * @throws BusinessException 格式非法或时间越界时抛出（错误码 1004）
     */
    private OffsetDateTime parseEventTime(String rawEventTime) {
        OffsetDateTime eventTime;
        try {
            eventTime = OffsetDateTime.parse(rawEventTime);
        } catch (DateTimeParseException e) {
            throw new BusinessException(RecErrorCode.EVENT_TIME_INVALID, "行为时间格式非法，需为带时区的 ISO 8601");
        }
        long deviationMinutes = Math.abs(Duration.between(eventTime.toInstant(), Instant.now()).toMinutes());
        if (deviationMinutes > EVENT_TIME_TOLERANCE_MINUTES) {
            throw new BusinessException(RecErrorCode.EVENT_TIME_INVALID,
                    "行为时间与服务器时间偏差超过 " + EVENT_TIME_TOLERANCE_MINUTES + " 分钟");
        }
        return eventTime;
    }

    /**
     * 商品存在性校验
     *
     * @param itemId 商品标识
     * @throws BusinessException 商品不存在或已删除时抛出（错误码 1003）
     */
    private void checkItemExists(Long itemId) {
        if (productMapper.selectById(itemId) == null) {
            throw new BusinessException(RecErrorCode.ITEM_NOT_FOUND, "商品不存在或已删除");
        }
    }

    /**
     * 组装待投递的行为事件（补齐服务端字段）
     *
     * @param dto       上报请求
     * @param userId    用户标识
     * @param eventTime 解析后的事件时间
     * @return 行为事件报文
     */
    private BehaviorEventDTO buildEvent(BehaviorReportDTO dto, Long userId, OffsetDateTime eventTime) {
        BehaviorEventDTO event = new BehaviorEventDTO();
        event.setEventId(dto.getEventId());
        event.setUserId(userId);
        event.setItemId(dto.getItemId());
        event.setEventType(dto.getEventType());
        // 统一以原始 ISO 8601 文本透传，由计算侧按同一口径解析，避免两侧序列化差异
        event.setEventTime(eventTime.toString());
        event.setDuration(dto.getDuration());
        event.setSource(0);
        event.setReceivedTime(OffsetDateTime.now().toString());
        return event;
    }

    /**
     * 组装待落库的行为明细
     *
     * @param dto       上报请求
     * @param userId    用户标识
     * @param eventTime 解析后的事件时间
     * @return 行为明细实体
     */
    private UserBehaviorLogDO buildBehaviorLog(BehaviorReportDTO dto, Long userId, OffsetDateTime eventTime) {
        UserBehaviorLogDO behaviorLog = new UserBehaviorLogDO();
        behaviorLog.setEventId(dto.getEventId());
        behaviorLog.setUserId(userId);
        behaviorLog.setItemId(dto.getItemId());
        behaviorLog.setEventType(dto.getEventType());
        // 事件时间按东八区落库（与库表时区配置一致）
        behaviorLog.setEventTime(eventTime.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
        behaviorLog.setDuration(dto.getDuration());
        behaviorLog.setReceivedTime(LocalDateTime.now());
        behaviorLog.setSource(0);
        return behaviorLog;
    }

    /**
     * 维护用户排除清单：购买与加购代表用户已作出决策，写入后由融合过滤环节排除
     *
     * <p>写入失败只记日志，不影响受理结果（对应详细设计 3.1 步骤 10）。
     *
     * @param dto    上报请求
     * @param userId 用户标识
     */
    private void maintainExcludeList(BehaviorReportDTO dto, Long userId) {
        if (!BehaviorTypes.needExclude(dto.getEventType())) {
            return;
        }
        try {
            int excludeDays = configService.getInt(RecConfigKeys.EXCLUDE_RECENT_DAYS);
            if (excludeDays <= 0) {
                return;
            }
            String key = RecRedisKeys.exclude(userId);
            recRedis.sadd(key, String.valueOf(dto.getItemId()));
            recRedis.expire(key, (long) excludeDays * 24 * 3600L);
        } catch (Exception e) {
            log.warn("排除清单写入失败, userId: {}, itemId: {}", userId, dto.getItemId(), e);
        }
    }

    /**
     * 记录被丢弃的事件
     *
     * <p>丢弃一律计日志，便于排查「上报量正常但特征覆盖率为零」这类问题。
     * 指标落库由 M-10 统一承担，此处只输出结构化日志。
     *
     * @param userId 用户标识
     * @param dto    上报请求
     * @param reject 丢弃原因
     * @param source 来源标识
     */
    private void recordReject(Long userId, BehaviorReportDTO dto, CollectReject reject, String source) {
        log.info("行为事件被丢弃, userId: {}, itemId: {}, eventType: {}, 原因: {}, source: {}",
                userId, dto.getItemId(), dto.getEventType(), reject.getReason(), source);
    }
}
