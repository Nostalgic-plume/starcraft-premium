package com.shop.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.BusinessException;
import com.shop.common.StpUserUtil;
import com.shop.mapper.RoleMapper;
import com.shop.mapper.UserMapper;
import com.shop.mapper.UserRoleMapper;
import com.shop.model.dto.UserLoginDTO;
import com.shop.model.dto.UserRegisterDTO;
import java.util.List;
import com.shop.model.entity.RoleDO;
import com.shop.model.entity.UserDO;
import com.shop.model.entity.UserRoleDO;
import com.shop.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户服务实现
 * 使用 Sa-Token 管理登录态，BCrypt 加密密码存储
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;

    /**
     * 用户注册：校验用户名唯一性，BCrypt 加密密码后入库，并自动赋予普通用户（user）角色
     *
     * @param dto 注册参数（username、password、phone，nickname 可选，缺省取用户名）
     * @throws BusinessException 用户名已存在
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(UserRegisterDTO dto) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<UserDO>()
                        .eq(UserDO::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BusinessException("用户名已存在");
        }

        UserDO user = new UserDO();
        user.setUsername(dto.getUsername());
        user.setPassword(DigestUtil.bcrypt(dto.getPassword()));
        user.setPhone(dto.getPhone());
        user.setNickname(dto.getNickname() != null ? dto.getNickname() : dto.getUsername());
        user.setStatus(0);

        userMapper.insert(user);

        // 自动赋予普通用户角色（user_role 关联表）
        RoleDO userRole = roleMapper.selectOne(
                new LambdaQueryWrapper<RoleDO>().eq(RoleDO::getCode, "user"));
        if (userRole != null) {
            UserRoleDO ur = new UserRoleDO();
            ur.setUserId(user.getId());
            ur.setRoleId(userRole.getId());
            userRoleMapper.insert(ur);
        }

        log.info("用户注册成功, username: {}", dto.getUsername());
    }

    /**
     * 用户登录：校验用户名与 BCrypt 密码，账号状态非 0（1=停用 2=注销中 3=封禁中）禁止登录并提示对应文案，
     * 通过后以 Sa-Token「user」设备类型建立登录会话（便于封禁时精准踢下线）
     *
     * @param dto 登录参数（username、password，均必填）
     * @return 登录令牌 token
     * @throws BusinessException 用户名或密码错误、账号被停用/注销中/封禁中（含对应提示文案）
     */
    @Override
    public String login(UserLoginDTO dto) {
        UserDO user = userMapper.selectOne(
                new LambdaQueryWrapper<UserDO>()
                        .eq(UserDO::getUsername, dto.getUsername()));
        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }

        if (!DigestUtil.bcryptCheck(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 账号状态校验：0正常可登录，1停用/2注销中/3封禁中均禁止登录
        if (user.getStatus() != null && user.getStatus() != 0) {
            String statusMsg = switch (user.getStatus()) {
                case 1 -> "账号已被停用，请联系管理员";
                case 2 -> "账号正在注销中，无法登录";
                case 3 -> "账号已被封禁，请联系管理员";
                default -> "账号状态异常，无法登录";
            };
            throw new BusinessException(statusMsg);
        }

        // 双参登录指定设备类型 device="user"：后续封号/停用可精准踢下线该用户会话，
        // 避免与同 id 的管理员（device="admin"）/商家（device="shop"）会话误伤
        StpUserUtil.INSTANCE.login(user.getId());

        log.info("用户登录成功, userId: {}", user.getId());
        return StpUserUtil.INSTANCE.getTokenValue();
    }

    /**
     * 按 ID 查询用户
     *
     * @param id 用户ID，必填
     * @return 用户实体 UserDO
     * @throws BusinessException 用户不存在
     */
    @Override
    public UserDO getById(Long id) {
        UserDO user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return user;
    }

    /**
     * 获取当前登录用户信息
     *
     * @return 当前用户实体 UserDO
     * @throws BusinessException 用户不存在（登录态对应的 userId 查无记录）
     */
    @Override
    public UserDO getCurrentUser() {
        long userId = StpUserUtil.INSTANCE.getLoginIdAsLong();
        return getById(userId);
    }

    /**
     * 查询全部用户列表（供需要遍历用户数据的场景使用）
     *
     * @return 用户列表 {@code List<UserDO>}
     */
    @Override
    public List<UserDO> listAll() {
        return userMapper.selectList(null);
    }
}
