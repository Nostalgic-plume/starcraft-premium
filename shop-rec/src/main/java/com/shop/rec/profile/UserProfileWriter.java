package com.shop.rec.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shop.rec.mapper.UserProfileMapper;
import com.shop.rec.model.entity.UserProfileDO;
import com.shop.rec.model.vo.PrefItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户画像版本写入（M-03 的事务边界）
 *
 * <p>
 * 独立成类的原因：Spring 的 {@code @Transactional} 基于代理生效，同类内部方法调用不经过代理，
 * 注解会失效。画像的「写入新版本 + 切换有效性 + 裁剪历史版本」必须处于同一事务，
 * 因此把事务操作集中在本类，由 {@link UserProfileService} 调用。
 *
 * <p>
 * <b>事务管理器</b>：显式指定 {@code recTransactionManager}——工程内存在推荐库与商城库
 * 两个数据源，不指定时按类型注入会拿到 {@code @Primary} 的商城库事务管理器，导致事务
 * 与数据源错配。
 *
 * @author shop
 */
@Slf4j
@Service
@Profile("rec")
@RequiredArgsConstructor
public class UserProfileWriter {

    /** 画像版本状态：有效 */
    public static final int STATUS_ACTIVE = 0;

    /** 画像版本状态：历史版本 */
    public static final int STATUS_HISTORY = 1;

    /** 保留的历史版本数（对应需求分析 4.3「至少保留最近 3 个历史版本」） */
    public static final int KEEP_VERSIONS = 3;

    private final UserProfileMapper userProfileMapper;

    /**
     * 写入新版本画像并切换有效性
     *
     * <p>三步在同一事务内完成（对应详细设计 3.3 处理步骤 5~7）：
     * 写入当日版本且置为有效 → 同用户其它版本置为历史 → 裁剪超出保留范围的更早版本。
     * 这样保证任一时刻同一用户只有一个有效版本。
     *
     * @param userId        用户标识
     * @param version       版本号（yyyyMMdd）
     * @param categoryPrefs 类目偏好（前 N 名）
     * @param brandPrefs    品牌偏好（前 N 名）
     * @param behaviorCount 参与归纳的行为条数
     */
    @Transactional(rollbackFor = Exception.class, transactionManager = "recTransactionManager")
    public void saveVersion(Long userId, int version, List<PrefItemVO> categoryPrefs,
                            List<PrefItemVO> brandPrefs, int behaviorCount) {
        // 覆盖写入当日版本（重跑任务时幂等）
        userProfileMapper.delete(new LambdaQueryWrapper<UserProfileDO>()
                .eq(UserProfileDO::getUserId, userId)
                .eq(UserProfileDO::getVersion, version));

        UserProfileDO profile = new UserProfileDO();
        profile.setUserId(userId);
        profile.setVersion(version);
        profile.setCategoryPref(categoryPrefs);
        profile.setBrandPref(brandPrefs);
        profile.setBehaviorCount(behaviorCount);
        profile.setStatus(STATUS_ACTIVE);
        userProfileMapper.insert(profile);

        // 同一事务内把其它版本置为历史
        userProfileMapper.update(null, new LambdaUpdateWrapper<UserProfileDO>()
                .eq(UserProfileDO::getUserId, userId)
                .ne(UserProfileDO::getVersion, version)
                .set(UserProfileDO::getStatus, STATUS_HISTORY));

        trimVersions(userId);
    }

    /**
     * 画像版本回滚（外部接口 E-08 的事务实现）
     *
     * <p>把目标历史版本置为有效、原有效版本置为历史，并清理该用户的画像缓存，
     * 使回滚后下一个请求即按目标版本提供偏好数据（详细设计 3.3 步骤 9、5.1.8）。
     *
     * @param userId        用户标识
     * @param targetVersion 目标版本号
     * @throws IllegalArgumentException 目标版本不在保留范围内时抛出
     */
    @Transactional(rollbackFor = Exception.class, transactionManager = "recTransactionManager")
    public void rollback(Long userId, int targetVersion) {
        UserProfileDO target = userProfileMapper.selectOne(new LambdaQueryWrapper<UserProfileDO>()
                .eq(UserProfileDO::getUserId, userId)
                .eq(UserProfileDO::getVersion, targetVersion));
        if (target == null) {
            throw new IllegalArgumentException("目标版本不在保留范围内");
        }

        userProfileMapper.update(null, new LambdaUpdateWrapper<UserProfileDO>()
                .eq(UserProfileDO::getUserId, userId)
                .set(UserProfileDO::getStatus, STATUS_HISTORY));
        userProfileMapper.update(null, new LambdaUpdateWrapper<UserProfileDO>()
                .eq(UserProfileDO::getUserId, userId)
                .eq(UserProfileDO::getVersion, targetVersion)
                .set(UserProfileDO::getStatus, STATUS_ACTIVE));
    }

    /**
     * 裁剪画像版本，仅保留最近 {@value #KEEP_VERSIONS} 个
     *
     * @param userId 用户标识
     */
    private void trimVersions(Long userId) {
        List<UserProfileDO> versions = userProfileMapper.selectVersionsByUserId(userId);
        if (versions.size() <= KEEP_VERSIONS) {
            return;
        }
        for (int i = KEEP_VERSIONS; i < versions.size(); i++) {
            userProfileMapper.deleteById(versions.get(i).getId());
        }
    }
}
