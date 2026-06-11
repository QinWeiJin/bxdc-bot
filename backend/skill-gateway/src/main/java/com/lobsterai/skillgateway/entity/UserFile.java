package com.lobsterai.skillgateway.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户文件实体。
 * <p>
 * 记录用户上传到 FTP 的文件元数据，存储于 MySQL 便于查询和关联。
 * 实际文件内容存储在 FTP 服务器上。
 * </p>
 */
@TableName("user_files")
public class UserFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** AAM 统一认证用户 ID */
    @TableField("user_id")
    private String userId;

    /** 用户上传的原始文件名（显示用），如 "报表.xlsx" */
    @TableField("original_file_name")
    private String originalFileName;

    /** FTP 存储用的文件名（UUID + 扩展名），如 "a1b2c3.xlsx"，避免同名冲突 */
    @TableField("file_name")
    private String fileName;

    /** 文件大小（字节） */
    @TableField("file_size")
    private Long fileSize;

    /** 文件类型（扩展名小写），如 docx, xlsx, csv, txt, md, py */
    @TableField("file_type")
    private String fileType;

    /** FTP 存储路径 */
    @TableField("ftp_path")
    private String ftpPath;

    /** 文件下载 URL（生成后存库，避免重复计算） */
    @TableField("download_url")
    private String downloadUrl;

    /** 文件解析后的 JSON 摘要（解析完成后填充） */
    @TableField("parsed_summary")
    private String parsedSummary;

    /** 上传时间 */
    @TableField(value = "upload_time", fill = FieldFill.INSERT)
    private LocalDateTime uploadTime;

    // ========== Getters & Setters ==========

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getFtpPath() {
        return ftpPath;
    }

    public void setFtpPath(String ftpPath) {
        this.ftpPath = ftpPath;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    public String getParsedSummary() {
        return parsedSummary;
    }

    public void setParsedSummary(String parsedSummary) {
        this.parsedSummary = parsedSummary;
    }

    public LocalDateTime getUploadTime() {
        return uploadTime;
    }

    public void setUploadTime(LocalDateTime uploadTime) {
        this.uploadTime = uploadTime;
    }
}
