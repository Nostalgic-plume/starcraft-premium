package com.shop.rec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.rec.model.entity.UserBehaviorLogDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户行为明细 Mapper（推荐库）
 * 提供行为明细表（t_user_behavior_log）的基础 CRUD，供 M-01 异步落库与 M-03 画像取数使用
 *
 * @author shop
 */
public interface UserBehaviorLogMapper extends BaseMapper<UserBehaviorLogDO> {

    /**
     * 按用户分片取画像计算所需的近期行为
     *
     * <p>对应详细设计 3.3 处理步骤 2、3：按用户标识取模分片逐片处理，
     * 分片可把单次任务的数据量摊到多轮查询，避免一次性加载全量行为。
     * 只取画像所需的三个字段，不读取停留时长等无关字段。
     *
     * @param since  起始事件时间（含），通常为当前时间往前 30 天
     * @param shards 分片总数
     * @param shard  当前分片序号（0 起）
     * @return 该分片内的行为记录（按用户标识升序，便于顺序归集）
     */
    @Select("SELECT user_id, item_id, event_type FROM t_user_behavior_log "
            + "WHERE event_time >= #{since} AND MOD(user_id, #{shards}) = #{shard} "
            + "ORDER BY user_id")
    List<UserBehaviorLogDO> selectForProfile(@Param("since") LocalDateTime since,
                                             @Param("shards") int shards,
                                             @Param("shard") int shard);
}
