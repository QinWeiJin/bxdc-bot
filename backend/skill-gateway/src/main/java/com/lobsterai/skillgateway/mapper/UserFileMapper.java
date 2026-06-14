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
