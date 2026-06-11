package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.config.FtpConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 本地文件存储服务（原 FTP 改为本地磁盘）。
 * <p>
 * 负责智能文件中心的所有文件操作：上传、下载、删除、列表、目录创建。
 * 文件存储在本地磁盘，路径由 {@link FtpConfig#getBasePath()} 指定。
 * 所有方法签名与原 FtpFileService 完全兼容，调用方零改动。
 * </p>
 */
@Service
public class FtpFileService {

    private static final Logger log = LoggerFactory.getLogger(FtpFileService.class);

    private final FtpConfig ftpConfig;

    public FtpFileService(FtpConfig ftpConfig) {
        this.ftpConfig = ftpConfig;
    }

    // ========== 公开 API（与原 FtpFileService 签名完全一致） ==========

    /**
     * 确保用户目录存在。
     */
    public boolean ensureUserDirectory(String userId) throws IOException {
        Path dir = resolveUserPath(userId);
        if (Files.exists(dir)) {
            return true;
        }
        Files.createDirectories(dir);
        log.info("Created user directory: {}", dir);
        return true;
    }

    /**
     * 上传文件。
     * <p>
     * 存储文件名由 UUID + 原扩展名生成（如 a1b2c3d4.xlsx），
     * 避免同名文件冲突，原始文件名存于 UserFile.originalFileName。
     * </p>
     *
     * @return 上传后的完整路径（如 /files/userId/a1b2c3d4.xlsx）
     */
    public String uploadFile(String userId, String originalFileName, InputStream inputStream) throws IOException {
        String storageFileName = generateStorageFileName(originalFileName);
        Path userDir = resolveUserPath(userId);
        Files.createDirectories(userDir);
        Path targetFile = userDir.resolve(storageFileName);

        try (FileOutputStream fos = new FileOutputStream(targetFile.toFile())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = inputStream.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        }

        String fullPath = ftpConfig.getBasePath() + "/" + userId + "/" + storageFileName;
        log.info("File saved: {} (user={}, original={})", targetFile, userId, originalFileName);
        return fullPath;
    }

    /**
     * 下载文件内容到内存字节流。
     */
    public ByteArrayOutputStream downloadFile(String userId, String fileName) throws IOException {
        Path filePath = resolveUserPath(userId).resolve(fileName);
        if (!Files.exists(filePath)) {
            throw new IOException("File not found: " + filePath);
        }
        try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos;
        }
    }

    /**
     * 获取文件大小（字节）。
     *
     * @return 文件大小，-1 表示文件不存在
     */
    public long getFileSize(String userId, String fileName) throws IOException {
        Path filePath = resolveUserPath(userId).resolve(fileName);
        if (!Files.exists(filePath)) {
            return -1;
        }
        return Files.size(filePath);
    }

    /**
     * 删除指定文件。
     *
     * @return true 如果删除成功
     */
    public boolean deleteFile(String userId, String fileName) throws IOException {
        Path filePath = resolveUserPath(userId).resolve(fileName);
        if (Files.exists(filePath)) {
            Files.delete(filePath);
            log.info("File deleted: {} (user={})", filePath, userId);
            return true;
        }
        return false;
    }

    /**
     * 列出用户目录下所有文件（返回本地 File 列表）。
     */
    public List<File> listFiles(String userId) throws IOException {
        Path userDir = resolveUserPath(userId);
        if (!Files.exists(userDir)) {
            return Collections.emptyList();
        }
        File[] files = userDir.toFile().listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        List<File> fileList = new ArrayList<File>();
        for (File f : files) {
            if (f.isFile()) {
                fileList.add(f);
            }
        }
        return fileList;
    }

    /**
     * 检查文件是否存在。
     */
    public boolean fileExists(String userId, String fileName) throws IOException {
        Path filePath = resolveUserPath(userId).resolve(fileName);
        return Files.exists(filePath);
    }

    /**
     * 删除用户目录下所有文件。
     *
     * @return 删除的文件数量
     */
    public int deleteAllFiles(String userId) throws IOException {
        Path userDir = resolveUserPath(userId);
        if (!Files.exists(userDir)) {
            return 0;
        }
        File[] files = userDir.toFile().listFiles();
        if (files == null) {
            return 0;
        }
        int deleted = 0;
        for (File f : files) {
            if (f.isFile()) {
                Files.delete(f.toPath());
                deleted++;
            }
        }
        log.info("Cleared {} files from user directory: {}", deleted, userDir);
        return deleted;
    }

    /**
     * 检查存储服务可用性（本地磁盘始终可用）。
     */
    public boolean isAvailable() {
        return true;
    }

    /**
     * 生成存储用的 UUID 短文件名。
     * <p>
     * 格式：{UUID 前 8 位}{原扩展名}，如 "a1b2c3d4.xlsx"。
     * </p>
     */
    public static String generateStorageFileName(String originalFileName) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String shortUuid = uuid.substring(0, 8);
        String ext = extractExtension(originalFileName);
        if (!ext.isEmpty()) {
            return shortUuid + "." + ext;
        }
        return shortUuid;
    }

    // ========== 内部方法 ==========

    private Path resolveUserPath(String userId) {
        String base = ftpConfig.getBasePath();
        if (base.startsWith("/")) {
            // 去掉前导 /，转为 Windows 绝对路径
            return Paths.get("E:/bxdc-ftp-data" + base, userId);
        }
        return Paths.get(base, userId);
    }

    private static String extractExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase();
    }
}
