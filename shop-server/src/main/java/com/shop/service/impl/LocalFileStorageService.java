package com.shop.service.impl;

import com.shop.common.BusinessException;
import com.shop.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 本地磁盘文件存储实现（单机/演示方案）
 * <p>
 * 落盘目录由 {@code app.upload.dir} 配置，按 {@code yyyy/MM/dd} 分片避免单目录文件过多；
 * 对外 URL 前缀由 {@code app.upload.url-prefix} 配置，须与 Nginx 容器挂载的静态目录一致。
 * </p>
 * <p>
 * 安全约定：文件名一律重新生成为 UUID，绝不采用用户提供的原始文件名（可能含 {@code ../} 等路径穿越载荷）；
 * 扩展名走白名单，且不使用 {@code Content-Type} 作为判定依据（该头部由客户端可控）。
 * </p>
 *
 * @author shop
 */
@Slf4j
@Service
public class LocalFileStorageService implements FileStorageService {

    /** 允许的图片扩展名白名单（小写） */
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp");

    /** 单文件大小上限，与 application.yml 的 multipart 配置保持一致 */
    private static final long MAX_SIZE_BYTES = 5 * 1024 * 1024L;

    private static final DateTimeFormatter DATE_DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 落盘根目录（绝对路径，已规范化） */
    private final Path rootDir;

    /** 对外 URL 前缀（不含结尾斜杠） */
    private final String urlPrefix;

    public LocalFileStorageService(
            @Value("${app.upload.dir:./uploads}") String dir,
            @Value("${app.upload.url-prefix:/uploads}") String urlPrefix) {
        this.rootDir = Paths.get(dir).toAbsolutePath().normalize();
        this.urlPrefix = urlPrefix.endsWith("/")
                ? urlPrefix.substring(0, urlPrefix.length() - 1)
                : urlPrefix;
        try {
            Files.createDirectories(this.rootDir);
        } catch (IOException e) {
            // 启动即失败优于运行期才发现写不了盘
            throw new IllegalStateException("无法创建上传根目录: " + this.rootDir, e);
        }
        log.info("本地文件存储初始化完成: rootDir={}, urlPrefix={}", this.rootDir, this.urlPrefix);
    }

    @Override
    public String saveImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的文件");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new BusinessException("图片大小不能超过 5MB");
        }

        String ext = resolveExtension(file.getOriginalFilename());
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException("仅支持 jpg / jpeg / png / gif / webp 格式的图片");
        }

        String relativeDir = LocalDate.now().format(DATE_DIR_FORMAT);
        // 文件名重新生成：原始文件名一律丢弃，从根本上消除路径穿越
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;

        Path targetDir = rootDir.resolve(relativeDir).normalize();
        Path target = targetDir.resolve(filename).normalize();

        // 第二道防线：规范化后仍须落在根目录内（文件名由 UUID 生成，此处为防御性校验）
        if (!target.startsWith(rootDir)) {
            throw new BusinessException("非法的文件路径");
        }

        try {
            Files.createDirectories(targetDir);
            file.transferTo(target);
        } catch (IOException e) {
            log.error("图片写入失败: target={}", target, e);
            throw new BusinessException("图片上传失败，请稍后重试");
        }

        String url = urlPrefix + "/" + relativeDir + "/" + filename;
        log.info("图片上传成功: url={}, size={} bytes", url, file.getSize());
        return url;
    }

    /**
     * 从原始文件名中提取扩展名（小写）
     * <p>
     * 原始文件名不可信，可能包含路径成分（{@code ../../evil.jpg}）或反斜杠变体，
     * 故先统一分隔符、只取最后一段，再截取最后一个点之后的部分。
     * </p>
     *
     * @param originalFilename 客户端提供的文件名，可为 null
     * @return 小写扩展名；无法解析时返回空串（调用方会因不在白名单而拒绝）
     */
    private String resolveExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }
        String name = originalFilename.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        int lastDot = name.lastIndexOf('.');
        if (lastDot < 0 || lastDot == name.length() - 1) {
            return "";
        }
        return name.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }
}
