package com.shop.service;

import com.shop.common.PageResult;
import com.shop.model.dto.AdminLoginDTO;
import com.shop.model.entity.AdminDO;
import com.shop.model.entity.UserDO;

import java.util.Map;

/**
 * 管理员服务
 * 提供登录认证、用户管理、状态变更等后台管理功能
 *
 * @author shop
 */
public interface AdminService {

    /**
     * 管理员登录（使用 admin 表独立账号）
     *
     * @param dto 登录参数（用户名、密码）
     * @return token、adminId、nickname
     */
    Map<String, Object> login(AdminLoginDTO dto);

    /**
     * 获取当前管理员信息
     *
     * @return 当前管理员实体
     */
    AdminDO getCurrentAdmin();

    /**
     * 分页查询用户列表（管理后台）
     *
     * @param page    页码
     * @param pageSize 每页条数
     * @param keyword  搜索关键词（用户名/昵称/手机号）
     * @param status   状态筛选（null=全部）
     * @return 分页结果
     */
    PageResult<UserDO> pageUsers(int page, int pageSize, String keyword, Integer status);

    /**
     * 修改用户状态
     * 权限校验：超级管理员可操作所有人，普通管理员不可操作超级管理员
     *
     * @param targetUserId 目标用户ID
     * @param newStatus    新状态
     * @param reason       变更原因
     */
    void updateUserStatus(Long targetUserId, Integer newStatus, String reason);

    /**
     * 批量修改用户状态
     *
     * @param targetUserIds 目标用户ID列表
     * @param newStatus     新状态
     * @param reason        变更原因
     */
    void batchUpdateUserStatus(java.util.List<Long> targetUserIds, Integer newStatus, String reason);
}
