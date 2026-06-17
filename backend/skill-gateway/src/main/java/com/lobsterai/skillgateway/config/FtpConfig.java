package com.lobsterai.skillgateway.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(FtpConfig.class);

    /** 下载链接签名算法 */
    private static final String HMAC_ALGO = "HmacSHA256";

    /** 签名密钥（不可暴露），用于下载链接的 token 生成与校验 */
    private static final String TOKEN_SECRET = "bxdc-download-token-key-2024";

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
     * 构建文件下载 URL（带签名 token，浏览器点击即可下载）。
     *
     * @param fileId 文件实体 ID
     * @param userId 文件所属用户 ID
     * @return 完整下载 URL，如 http://localhost:18080/api/files/download/49?token=xxx
     */
    public String buildDownloadUrl(Long fileId, String userId) {
        String base = downloadBaseUrl.endsWith("/") ? downloadBaseUrl : downloadBaseUrl + "/";
        return base + "api/files/download/" + fileId + "?token=" + generateDownloadToken(fileId, userId);
    }

    /**
     * 构建文件下载 URL（不带 token，仅供内部使用）。
     */
    public String buildDownloadUrl(Long fileId) {
        String base = downloadBaseUrl.endsWith("/") ? downloadBaseUrl : downloadBaseUrl + "/";
        return base + "api/files/download/" + fileId;
    }

    /**
     * 生成下载签名 token。
     * <p>
     * 格式：base64url(HMAC-SHA256(fileId:userId, secret)) 截取前 32 字符。
     * 浏览器点击链接时通过 token 校验身份，无需 X-User-Id header。
     * </p>
     */
    public String generateDownloadToken(Long fileId, String userId) {
        try {
            String payload = fileId + ":" + userId;
            Mac mac = Mac.getInstance(HMAC_ALGO);
            SecretKeySpec keySpec = new SecretKeySpec(TOKEN_SECRET.getBytes(StandardCharsets.UTF_8), HMAC_ALGO);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return base64.substring(0, Math.min(32, base64.length()));
        } catch (Exception e) {
            log.error("Failed to generate download token for fileId={} userId={}", fileId, userId, e);
            return "";
        }
    }

    /**
     * 校验下载 token：用 fileId + userId 重新签名后比对。
     *
     * @return true 表示 token 有效
     */
    public boolean verifyDownloadToken(Long fileId, String userId, String token) {
        if (token == null || token.isEmpty() || userId == null) {
            return false;
        }
        String expected = generateDownloadToken(fileId, userId);
        return expected.equals(token);
    }
}
