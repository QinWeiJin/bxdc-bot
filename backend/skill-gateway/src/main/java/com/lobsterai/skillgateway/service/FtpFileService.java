package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.config.FtpConfig;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * FTP 文件存储服务。
 * <p>
 * 负责智能文件中心的所有 FTP 文件操作：
 * 上传、下载、删除、列表、目录创建。
 * 每次操作独立建立连接，操作完成后断开，保证线程安全。
 * </p>
 */
@Service
public class FtpFileService {

    private static final Logger log = LoggerFactory.getLogger(FtpFileService.class);

    private final FtpConfig ftpConfig;

    public FtpFileService(FtpConfig ftpConfig) {
        this.ftpConfig = ftpConfig;
    }

    /**
     * 确保用户 FTP 目录存在，不存在则创建。
     *
     * @param userId AAM 用户 ID
     * @return true 如果目录已存在或创建成功
     * @throws IOException FTP 连接或操作失败
     */
    public boolean ensureUserDirectory(String userId) throws IOException {
        FTPClient ftp = connect();
        try {
            String userPath = ftpConfig.buildUserPath(userId);
            if (directoryExists(ftp, userPath)) {
                return true;
            }
            return makeDirectories(ftp, userPath);
        } finally {
            disconnect(ftp);
        }
    }

    /**
     * 上传文件到用户 FTP 目录。
     * <p>
     * FTP 存储文件名由 UUID + 原扩展名生成（如 a1b2c3d4.xlsx），
     * 避免同名文件冲突，原始文件名存于 UserFile.originalFileName。
     * </p>
     *
     * @param userId           AAM 用户 ID
     * @param originalFileName 用户上传的原始文件名（用于提取扩展名）
     * @param inputStream      文件输入流
     * @return 上传后的 FTP 完整路径（UUID 文件名）
     * @throws IOException FTP 操作失败
     */
    public String uploadFile(String userId, String originalFileName, InputStream inputStream) throws IOException {
        String storageFileName = generateStorageFileName(originalFileName);
        FTPClient ftp = connect();
        try {
            String userPath = ftpConfig.buildUserPath(userId);
            if (!directoryExists(ftp, userPath)) {
                makeDirectories(ftp, userPath);
            }
            if (!ftp.changeWorkingDirectory(userPath)) {
                throw new IOException("Cannot enter user directory: " + userPath);
            }
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            if (!ftp.storeFile(storageFileName, inputStream)) {
                throw new IOException("FTP storeFile failed: " + storageFileName);
            }
            String fullPath = userPath + "/" + storageFileName;
            log.info("File uploaded: {} (user={}, original={})", fullPath, userId, originalFileName);
            return fullPath;
        } finally {
            disconnect(ftp);
        }
    }

    /**
     * 下载文件内容到内存字节流。
     *
     * @param userId   AAM 用户 ID
     * @param fileName 文件名
     * @return 文件内容的字节流
     * @throws IOException FTP 操作失败或文件不存在
     */
    public ByteArrayOutputStream downloadFile(String userId, String fileName) throws IOException {
        FTPClient ftp = connect();
        try {
            String userPath = ftpConfig.buildUserPath(userId);
            if (!ftp.changeWorkingDirectory(userPath)) {
                throw new IOException("User directory not found: " + userPath);
            }
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (!ftp.retrieveFile(fileName, baos)) {
                throw new IOException("File not found or download failed: " + fileName);
            }
            return baos;
        } finally {
            disconnect(ftp);
        }
    }

    /**
     * 打开文件读取流（用于流式下载，避免全量加载到堆）。
     * <p>
     * 调用方必须 {@code try-with-resources} 关闭。
     * 注意：Stream 关闭时 FTP 连接不会自动断开（独立连接），
     * 大文件场景建议用 downloadFile 走 ByteArrayOutputStream。
     * </p>
     */
    public InputStream openForDownload(String userId, String fileName) throws IOException {
        FTPClient ftp = connect();
        try {
            String userPath = ftpConfig.buildUserPath(userId);
            if (!ftp.changeWorkingDirectory(userPath)) {
                throw new IOException("User directory not found: " + userPath);
            }
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            return ftp.retrieveFileStream(fileName);
        } catch (IOException e) {
            disconnect(ftp);
            throw e;
        }
    }

    /**
     * 获取文件大小（字节）。
     *
     * @param userId   AAM 用户 ID
     * @param fileName 文件名
     * @return 文件大小，-1 表示文件不存在
     * @throws IOException FTP 操作失败
     */
    public long getFileSize(String userId, String fileName) throws IOException {
        FTPClient ftp = connect();
        try {
            String userPath = ftpConfig.buildUserPath(userId);
            if (!ftp.changeWorkingDirectory(userPath)) {
                return -1;
            }
            FTPFile[] files = ftp.listFiles(fileName);
            if (files != null && files.length == 1 && files[0].isFile()) {
                return files[0].getSize();
            }
            return -1;
        } finally {
            disconnect(ftp);
        }
    }

    /**
     * 删除用户 FTP 目录下的文件。
     *
     * @param userId   AAM 用户 ID
     * @param fileName 文件名
     * @return true 如果删除成功
     * @throws IOException FTP 操作失败
     */
    public boolean deleteFile(String userId, String fileName) throws IOException {
        FTPClient ftp = connect();
        try {
            String userPath = ftpConfig.buildUserPath(userId);
            if (!ftp.changeWorkingDirectory(userPath)) {
                return false;
            }
            boolean deleted = ftp.deleteFile(fileName);
            if (deleted) {
                log.info("File deleted: {}/{} (user={})", userPath, fileName, userId);
            }
            return deleted;
        } finally {
            disconnect(ftp);
        }
    }

    /**
     * 列出用户 FTP 目录下的所有文件。
     *
     * @param userId AAM 用户 ID
     * @return 文件信息列表
     * @throws IOException FTP 操作失败
     */
    public List<FTPFile> listFiles(String userId) throws IOException {
        FTPClient ftp = connect();
        try {
            String userPath = ftpConfig.buildUserPath(userId);
            if (!ftp.changeWorkingDirectory(userPath)) {
                return Collections.emptyList();
            }
            FTPFile[] files = ftp.listFiles();
            if (files == null) {
                return Collections.emptyList();
            }
            List<FTPFile> fileList = new ArrayList<FTPFile>();
            for (FTPFile f : files) {
                if (f.isFile()) {
                    fileList.add(f);
                }
            }
            return fileList;
        } finally {
            disconnect(ftp);
        }
    }

    /**
     * 检查用户目录下是否存在指定文件。
     *
     * @param userId   AAM 用户 ID
     * @param fileName 文件名
     * @return true 如果文件存在
     * @throws IOException FTP 操作失败
     */
    public boolean fileExists(String userId, String fileName) throws IOException {
        FTPClient ftp = connect();
        try {
            String userPath = ftpConfig.buildUserPath(userId);
            if (!ftp.changeWorkingDirectory(userPath)) {
                return false;
            }
            FTPFile[] files = ftp.listFiles(fileName);
            return files != null && files.length == 1 && files[0].isFile();
        } finally {
            disconnect(ftp);
        }
    }

    /**
     * 删除用户 FTP 目录下的所有文件（清空目录）。
     *
     * @param userId AAM 用户 ID
     * @return 删除的文件数量
     * @throws IOException FTP 操作失败
     */
    public int deleteAllFiles(String userId) throws IOException {
        FTPClient ftp = connect();
        try {
            String userPath = ftpConfig.buildUserPath(userId);
            if (!ftp.changeWorkingDirectory(userPath)) {
                return 0;
            }
            FTPFile[] files = ftp.listFiles();
            if (files == null) {
                return 0;
            }
            int deleted = 0;
            for (FTPFile file : files) {
                if (file.isFile()) {
                    if (ftp.deleteFile(file.getName())) {
                        deleted++;
                    }
                }
            }
            log.info("Cleared {} files from user directory: {}", deleted, userPath);
            return deleted;
        } finally {
            disconnect(ftp);
        }
    }

    /**
     * 检查 FTP 服务连通性。
     *
     * @return true 如果 FTP 服务可用
     */
    public boolean isAvailable() {
        FTPClient ftp = null;
        try {
            ftp = connect();
            return true;
        } catch (IOException e) {
            log.warn("FTP service not available: {}", e.getMessage());
            return false;
        } finally {
            if (ftp != null) {
                disconnect(ftp);
            }
        }
    }

    /**
     * 生成 FTP 存储用的 UUID 文件名。
     * <p>
     * 格式：{UUID 前 8 位}{原扩展名}，如 "a1b2c3d4.xlsx"。
     * 仅取前 8 位以保持文件名简洁，空间内冲突概率极低。
     * </p>
     *
     * @param originalFileName 原始文件名（用于提取扩展名）
     * @return UUID 文件名
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

    /**
     * 建立 FTP 连接并登录。
     */
    private FTPClient connect() throws IOException {
        FTPClient ftp = new FTPClient();
        ftp.setConnectTimeout(ftpConfig.getConnectTimeout());
        ftp.setDataTimeout(ftpConfig.getDataTimeout());
        ftp.connect(ftpConfig.getHost(), ftpConfig.getPort());
        int reply = ftp.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            ftp.disconnect();
            throw new IOException("FTP server refused connection, reply: " + reply);
        }
        if (!ftp.login(ftpConfig.getUsername(), ftpConfig.getPassword())) {
            ftp.disconnect();
            throw new IOException("FTP login failed for user: " + ftpConfig.getUsername());
        }
        ftp.enterLocalPassiveMode();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        return ftp;
    }

    /**
     * 断开 FTP 连接。
     */
    private void disconnect(FTPClient ftp) {
        if (ftp != null && ftp.isConnected()) {
            try {
                ftp.logout();
                ftp.disconnect();
            } catch (IOException e) {
                log.warn("FTP disconnect error: {}", e.getMessage());
            }
        }
    }

    /**
     * 检查 FTP 目录是否存在。
     */
    private boolean directoryExists(FTPClient ftp, String path) throws IOException {
        String cwd = ftp.printWorkingDirectory();
        return ftp.changeWorkingDirectory(path) && ftp.changeWorkingDirectory(cwd);
    }

    /**
     * 递归创建 FTP 目录。
     */
    private boolean makeDirectories(FTPClient ftp, String path) throws IOException {
        String[] parts = path.split("/");
        StringBuilder current = new StringBuilder("/");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            current.append(part);
            if (!directoryExists(ftp, current.toString())) {
                if (!ftp.makeDirectory(current.toString())) {
                    log.error("FTP mkdir failed: {}", current);
                    return false;
                }
            }
            current.append("/");
        }
        return true;
    }

    /**
     * 提取文件扩展名（小写，不含点）。
     */
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
