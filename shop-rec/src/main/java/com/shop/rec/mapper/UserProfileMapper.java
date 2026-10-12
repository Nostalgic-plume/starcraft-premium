package com.shop.rec.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.rec.model.entity.UserProfileDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户画像 Mapper（推荐库）
 * 提供画像表（t_user_profile）的基础 CRUD，供 M-03 画像生成与版本回滚使用
 *
 * @author shop
 */
public interface UserProfileMapper extends BaseMapper<UserProfileDO> {

    /**
     * 查询指定用户的所有画像版本（按版本号降序）
     *
     * <p>供 E-08 画像版本回滚校验目标版本是否在保留范围内（详细设计 3.3 步骤 9）。
     *
     * @param userId 用户标识
     * @return 画像版本列表
     */
    @Select("SELECT id, user_id, version, status, behavior_count FROM t_user_profile "
            + "WHERE user_id = #{userId} ORDER BY version DESC")
    List<UserProfileDO> selectVersionsByUserId(@Param("userId") Long userId);
}
