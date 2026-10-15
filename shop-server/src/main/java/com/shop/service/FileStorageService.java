package com.shop.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储服务
 * <p>
 * 抽象出存储层是为了让「本地磁盘」与「对象存储（OSS/COS/MinIO）」可互换：
 * 当前实现为 {@code LocalFileStorageService}（单机演示），生产环境替换实现即可，
 * 接口契约（返回可直接访问的 URL）与调用方均不变。
 * </p>
 *
 * @author shop
 */
public interface FileStorageService {

    /**
     * 保存图片文件
     *
     * @param file 上传的图片文件，非空
     * @return 可直接用于前端展示的相对 URL，形如 {@code /uploads/2026/10/13/<uuid>.jpg}
     * @throws com.shop.common.BusinessException 文件为空、超过大小上限、扩展名不在白名单、写盘失败
     */
    String saveImage(MultipartFile file);
}
