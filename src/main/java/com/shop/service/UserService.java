package com.shop.service;

import com.shop.model.dto.UserLoginDTO;
import com.shop.model.dto.UserRegisterDTO;
import com.shop.model.entity.UserDO;

import java.util.List;

/**
 * 用户服务
 * 处理用户注册、登录、个人信息查询等业务逻辑
 */
public interface UserService {

    /**
     * 用户注册
     * 校验用户名唯一性，BCrypt 加密密码后入库
     *
     * @param dto 注册参数（用户名、密码、手机号、昵称）
     * @throws BusinessException 用户名已存在时抛出
     */
    void register(UserRegisterDTO dto);

    /**
     * 用户登录
     * 校验密码，通过 Sa-Token 签发登录令牌
     *
     * @param dto 登录参数（用户名、密码）
     * @return Sa-Token 令牌字符串
     * @throws BusinessException 用户名或密码错误、账号被禁用时抛出
     */
    String login(UserLoginDTO dto);

    /**
     * 根据 ID 查询用户
     *
     * @param id 用户 ID
     * @return 用户实体，不存在时抛异常
     * @throws BusinessException 用户不存在时抛出
     */
    UserDO getById(Long id);

    /**
     * 获取当前登录用户
     * 从 Sa-Token 上下文中获取当前用户 ID 并查询完整信息
     *
     * @return 当前登录用户实体
     * @throws BusinessException 未登录或用户不存在时抛出
     */
    UserDO getCurrentUser();

    /**
     * 查询所有用户（管理后台用）
     *
     * @return 用户列表
     */
    List<UserDO> listAll();
}
