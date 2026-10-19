package com.shop.controller;

import cn.dev33.satoken.exception.NotLoginException;
import com.shop.common.Result;
import com.shop.common.StpAdminUtil;
import com.shop.common.StpShopUtil;
import com.shop.common.StpUserUtil;
import com.shop.service.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传接口
 * 当前仅提供图片上传，落盘细节由 {@link FileStorageService} 实现决定
 *
 * @author shop
 */
@Slf4j
@Tag(name = "文件上传模块")
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private final FileStorageService fileStorageService;

    /**
     * 上传商品图片，返回可直接用于前端展示的相对 URL
     * <p>
     * 登录校验刻意不用 {@code @SaCheckLogin}：该注解不带 {@code type} 时默认校验
     * {@code loginType="login"}，而本项目三端分别使用 user / shop / admin，
     * 从未以 "login" 登录过，带上注解会永远返回 401。
     * 故改为代码判断三端任一已登录即放行，并抛 {@link NotLoginException} 以保留 401 语义。
     * </p>
     *
     * @param file 上传的图片文件（表单字段名 file），支持 jpg/jpeg/png/gif/webp，单文件 ≤5MB
     * @return 图片访问 URL，形如 {@code /uploads/2026/10/13/<uuid>.jpg}
     */
    @PostMapping("/image")
    @Operation(summary = "上传商品图片")
    public Result<String> uploadImage(@RequestParam("file") MultipartFile file) {
        if (!StpUserUtil.INSTANCE.isLogin()
                && !StpShopUtil.INSTANCE.isLogin()
                && !StpAdminUtil.INSTANCE.isLogin()) {
            throw new NotLoginException("未登录，请先登录",
                    StpShopUtil.INSTANCE.getLoginType(), NotLoginException.NOT_TOKEN);
        }
        return Result.success("上传成功", fileStorageService.saveImage(file));
    }
}
