package com.shop.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.BusinessException;
import com.shop.common.PageResult;
import com.shop.constant.AccountStatusEnum;
import com.shop.constant.AdminLevelEnum;
import com.shop.mapper.AdminMapper;
import com.shop.mapper.UserMapper;
import com.shop.mapper.UserStatusLogMapper;
import com.shop.model.dto.AdminLoginDTO;
import com.shop.model.entity.AdminDO;
import com.shop.model.entity.UserDO;
import com.shop.model.entity.UserStatusLogDO;
import com.shop.service.AdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 管理员服务实现
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final AdminMapper adminMapper;
    private final UserMapper userMapper;
    private final UserStatusLogMapper userStatusLogMapper;

    @Override
    public Map<String, Object> login(AdminLoginDTO dto) {
        AdminDO admin = adminMapper.selectOne(
                new LambdaQueryWrapper<AdminDO>().eq(AdminDO::getUsername, dto.getUsername()));
        if (admin == null) {
            throw new BusinessException("管理员账号不存在");
        }
        if (!DigestUtil.bcryptCheck(dto.getPassword(), admin.getPassword())) {
            throw new BusinessException("密码错误");
        }
        if (admin.getStatus() != null && admin.getStatus() != 0) {
            throw new BusinessException("管理员账号已被停用");
        }
        StpUtil.login(admin.getId(), "admin");
        log.info("管理员登录成功, adminId: {}, username: {}", admin.getId(), admin.getUsername());
        return Map.of(
                "token", StpUtil.getTokenValue(),
                "adminId", admin.getId(),
                "nickname", admin.getNickname());
    }

    @Override
    public AdminDO getCurrentAdmin() {
        Long adminId = StpUtil.getLoginIdAsLong();
        AdminDO admin = adminMapper.selectById(adminId);
        if (admin == null) {
            throw new BusinessException("管理员不存在");
        }
        return admin;
    }

    @Override
    public PageResult<UserDO> pageUsers(int page, int pageSize, String keyword, Integer status) {
        LambdaQueryWrapper<UserDO> wrapper = new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getDeleted, 0);

        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(UserDO::getUsername, keyword)
                    .or().like(UserDO::getNickname, keyword)
                    .or().like(UserDO::getPhone, keyword));
        }
        if (status != null) {
            wrapper.eq(UserDO::getStatus, status);
        }
        wrapper.orderByDesc(UserDO::getCreateTime);

        Page<UserDO> p = userMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return PageResult.of(p);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserStatus(Long targetUserId, Integer newStatus, String reason) {
        long operatorId = StpUtil.getLoginIdAsLong();
        AdminDO operator = adminMapper.selectById(operatorId);
        if (operator == null) {
            throw new BusinessException("操作人不存在");
        }

        UserDO target = userMapper.selectById(targetUserId);
        if (target == null) {
            throw new BusinessException("目标用户不存在");
        }

        // 权限校验：操作人必须是有效管理员（admin 表 adminLevel：1=普通 2=超级）
        // user 表不存管理员级别（管理员已独立到 admin 表），目标用户恒为普通买家，无需比较目标级别
        Integer adminLevel = operator.getAdminLevel();
        if (adminLevel == null || !AdminLevelEnum.canOperate(adminLevel, AdminLevelEnum.NONE.getValue())) {
            log.warn("权限不足: 操作人={}(level={}), 目标={}",
                    operatorId, adminLevel, targetUserId);
            throw new BusinessException("权限不足，无法操作该用户");
        }

        int oldStatus = target.getStatus() != null ? target.getStatus() : 0;

        // 更新状态
        target.setStatus(newStatus);
        userMapper.updateById(target);

        // 记录操作日志
        UserStatusLogDO logDO = new UserStatusLogDO();
        logDO.setUserId(targetUserId);
        logDO.setOperatorId(operatorId);
        logDO.setFromStatus(oldStatus);
        logDO.setToStatus(newStatus);
        logDO.setReason(reason);
        userStatusLogMapper.insert(logDO);

        // 强制下线：状态非 0（停用/注销中/封禁中）即禁止登录，踢掉已登录会话。
        // 会话 key 为 {tokenName}:{loginType}:session:{loginId}，loginType 恒为 "login" 且不含 device，
        // 同 id 的 user/admin/shop 共享同一 SaSession，故必须按 device 精准踢（普通用户登录 device="user"），
        // 避免 `kickout(id, null)` 误伤同 id 的管理员/商家会话
        if (newStatus != null && newStatus != 0) {
            StpUtil.kickout(targetUserId, "user");
            log.info("用户强制下线: targetUserId={}, newStatus={}", targetUserId, newStatus);
        }

        log.info("用户状态变更: targetUserId={}, {}→{}, reason={}, operatorId={}",
                targetUserId, AccountStatusEnum.labelOf(oldStatus),
                AccountStatusEnum.labelOf(newStatus), reason, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateUserStatus(List<Long> targetUserIds, Integer newStatus, String reason) {
        for (Long id : targetUserIds) {
            updateUserStatus(id, newStatus, reason);
        }
        log.info("批量状态变更完成: count={}, newStatus={}", targetUserIds.size(), newStatus);
    }
}
