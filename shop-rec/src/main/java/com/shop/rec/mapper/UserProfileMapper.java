package com.shop.rec.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.rec.model.entity.UserProfileDO;

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
     * <p>供 E-08 画像版本回滚校验目标版本是否在保留范围内（详细设计 3.3 步骤 9），
     * 以及画像任务裁剪历史版本时判断保留范围。
     *
     * <p><b>必须用 Wrapper 查询而非自定义 @Select</b>：画像的 category_pref / brand_pref 是 JSON 列，
     * 依赖 {@code @TableName(autoResultMap = true)} 与 {@code JacksonTypeHandler} 完成
     * JSON ↔ List&lt;PrefItemVO&gt; 的映射，而 <b>autoResultMap 只对 BaseMapper 的内置方法生效，
     * 自定义 @Select 不会应用它</b>——用 @Select 查出来的偏好列恒为 null，
     * 会让「画像有无偏好」这类判断得到错误结论。
     *
     * @param userId 用户标识
     * @return 画像版本列表（含偏好内容）
     */
    default List<UserProfileDO> selectVersionsByUserId(Long userId) {
        return selectList(new LambdaQueryWrapper<UserProfileDO>()
                .eq(UserProfileDO::getUserId, userId)
                .orderByDesc(UserProfileDO::getVersion));
    }
}
