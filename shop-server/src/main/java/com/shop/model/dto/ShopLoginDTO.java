package com.shop.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 商家（店铺）登录请求
 * 商家使用 shop 表独立的账号密码登录，不经过 user 表
 */
@Schema(description = "商家登录请求")
public class ShopLoginDTO extends LoginDTO {
}
