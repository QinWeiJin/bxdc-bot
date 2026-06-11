package com.lobsterai.skillgateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * FTP 配置类。
 * <p>
 * 智能文件中心的文件存储配置，读取 application.properties 中的 FTP 连接参数。
 * </p>
 */
@Configuration
public class FtpConfig {

    @Value("${app.ftp.host:127.0.0.1}")
    private String host;

    @Value("${app.ftp.port:21}")
    private int port;

    @Value("${app.ftp.username:ftpuser}")
    private String username;

    @Value("${app.ftp.password:ftpuser}")
    private String password;

    @Value("${app.ftp.base-path:/files}")
    private String basePath;

    @Value("${app.ftp.connect-timeout:10000}")
    private int connectTimeout;

    @Value("${app.ftp.data-timeout:60000}")
    private int dataTimeout;

    @Value("${app.file.download-base-url:http://localhost:18080}")
    private String downloadBaseUrl;

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getBasePath() {
        return basePath;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public int getDataTimeout() {
        return dataTimeout;
    }

    public String getDownloadBaseUrl() {
        return downloadBaseUrl;
    }

    /**
     * 构建用户 FTP 目录路径。
     *
     * @param userId 用户 AAM ID
     * @return 完整 FTP 路径，如 /files/zhangsan
     */
    public String buildUserPath(String userId) {
        if (basePath.endsWith("/")) {
            return basePath + userId;
        }
        return basePath + "/" + userId;
    }

    /**
     * 构建文件下载 URL。
     *
     * @param fileId 文件实体 ID
     * @return 完整下载 URL
     */
    public String buildDownloadUrl(Long fileId) {
        String base = downloadBaseUrl.endsWith("/") ? downloadBaseUrl : downloadBaseUrl + "/";
        return base + "api/files/download/" + fileId;
    }
}
