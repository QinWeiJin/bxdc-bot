package com.lobsterai.skillgateway.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lobsterai.skillgateway.entity.UserFile;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/**
 * UserFile MyBatis-Plus Mapper。
 */
@Mapper
public interface UserFileMapper extends BaseMapper<UserFile> {

    /**
     * 按用户 ID 查所有文件（按上传时间倒序）。
     */
    default List<UserFile> findByUserId(String userId) {
        return selectList(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .orderByDesc(UserFile::getUploadTime));
    }

    /**
     * 按用户 ID + 原始文件名查文件（用于重复校验）。
     * 多文件同名时返回第一个（按上传时间倒序），不抛异常。
     */
    default Optional<UserFile> findByUserIdAndOriginalFileName(String userId, String originalFileName) {
        List<UserFile> list = selectList(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getOriginalFileName, originalFileName)
                .orderByDesc(UserFile::getUploadTime)
                .last("LIMIT 1"));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * open spec: temp-file-filtering — 按用户 ID 查用户上传的文件（排除 tool 生成文件）。
     * 用于文件列表查询，不展示 tool 生成的临时/新建文件。
     */
    default List<UserFile> findByUserIdExcludeToolGenerated(String userId) {
        return selectList(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getIsToolGenerated, 0)
                .orderByDesc(UserFile::getUploadTime));
    }

    /**
     * open spec: temp-file-filtering — 按用户 ID + 原始文件名查用户上传的文件（排除 tool 生成）。
     * 用于 check-duplicate 校验和上传覆盖，不对 tool 生成的文件做假阳性匹配。
     */
    default Optional<UserFile> findByUserIdAndOriginalFileNameExcludeToolGenerated(String userId, String originalFileName) {
        List<UserFile> list = selectList(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getOriginalFileName, originalFileName)
                .eq(UserFile::getIsToolGenerated, 0)
                .orderByDesc(UserFile::getUploadTime)
                .last("LIMIT 1"));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 按用户 ID + 原始文件名查文件（支持多文件同名时取 limit 条，按上传时间倒序）。
     */
    default List<UserFile> findByUserIdAndFileNameLimit(String userId, String originalFileName, int limit) {
        return selectList(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getOriginalFileName, originalFileName)
                .orderByDesc(UserFile::getUploadTime)
                .last("LIMIT " + limit));
    }

    /**
     * 按用户 ID + FTP 文件名查文件。
     */
    default Optional<UserFile> findByUserIdAndFileName(String userId, String fileName) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getFileName, fileName)));
    }
}
