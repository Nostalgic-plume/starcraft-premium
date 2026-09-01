package com.shop.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.BusinessException;
import com.shop.common.PageResult;
import com.shop.common.StpAdminUtil;
import com.shop.common.StpUserUtil;
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

    /**
     * 管理员登录：校验管理员账号存在性、BCrypt 密码与账号状态后，以 Sa-Token「admin」设备类型建立登录会话
     *
     * @param dto 登录请求参数（username 管理员用户名、password 明文密码，均必填）
     * @return 登录成功后返回 token、adminId、nickname 组成的 Map
     * @throws BusinessException 管理员账号不存在、密码错误、管理员账号已被停用
     */
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
        StpAdminUtil.INSTANCE.login(admin.getId());
        log.info("管理员登录成功, adminId: {}, username: {}", admin.getId(), admin.getUsername());
        return Map.of(
                "token", StpAdminUtil.INSTANCE.getTokenValue(),
                "adminId", admin.getId(),
                "nickname", admin.getNickname());
    }

    /**
     * 获取当前登录管理员信息
     *
     * @return 当前管理员实体 AdminDO
     * @throws BusinessException 管理员不存在（会话对应的 adminId 在 admin 表查无记录）
     */
    @Override
    public AdminDO getCurrentAdmin() {
        Long adminId = StpAdminUtil.INSTANCE.getLoginIdAsLong();
        AdminDO admin = adminMapper.selectById(adminId);
        if (admin == null) {
            throw new BusinessException("管理员不存在");
        }
        return admin;
    }

    /**
     * 管理员端用户分页查询，支持关键字（用户名/昵称/手机号模糊）与账号状态筛选，按创建时间倒序
     *
     * @param page     页码，从 1 开始
     * @param pageSize 每页条数
     * @param keyword  搜索关键字，可为空；非空时对 username、nickname、phone 做 OR 模糊匹配
     * @param status   账号状态筛选：0=正常 1=停用 2=注销中 3=封禁中，可为空表示不过滤
     * @return 用户分页结果 {@code PageResult<UserDO>}
     */
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

    /**
     * 修改单个用户账号状态（事务内）：校验操作人权限（adminLevel 1/2 可操作普通用户）后更新 user.status，
     * 记录 user_status_log 操作日志；新状态非 0 时按 device="user" 精准踢下线该用户已登录会话
     *
     * @param targetUserId 目标用户ID，必填
     * @param newStatus    新状态：0=正常 1=停用 2=注销中 3=封禁中
     * @param reason       状态变更原因，必填并写入日志
     * @throws BusinessException 操作人不存在、目标用户不存在、权限不足无法操作该用户
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserStatus(Long targetUserId, Integer newStatus, String reason) {
        long operatorId = StpAdminUtil.INSTANCE.getLoginIdAsLong();
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
            StpUserUtil.INSTANCE.kickout(targetUserId);
            log.info("用户强制下线: targetUserId={}, newStatus={}", targetUserId, newStatus);
        }

        log.info("用户状态变更: targetUserId={}, {}→{}, reason={}, operatorId={}",
                targetUserId, AccountStatusEnum.labelOf(oldStatus),
                AccountStatusEnum.labelOf(newStatus), reason, operatorId);
    }

    /**
     * 批量修改用户状态（事务内）：循环调用 updateUserStatus 逐用户处理并统一记录日志
     *
     * @param targetUserIds 目标用户ID列表，必填
     * @param newStatus     新状态：0=正常 1=停用 2=注销中 3=封禁中
     * @param reason        状态变更原因
     * @throws BusinessException 任一用户处理失败时抛出（同 updateUserStatus 的异常场景）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateUserStatus(List<Long> targetUserIds, Integer newStatus, String reason) {
        for (Long id : targetUserIds) {
            updateUserStatus(id, newStatus, reason);
        }
        log.info("批量状态变更完成: count={}, newStatus={}", targetUserIds.size(), newStatus);
    }
}
