package com.shop.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 管理员登录请求
 * 管理员使用 admin 表独立账号登录，不经过 user 表
 */
@Schema(description = "管理员登录请求")
public class AdminLoginDTO extends LoginDTO {
}
