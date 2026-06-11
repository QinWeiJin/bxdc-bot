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
     */
    default Optional<UserFile> findByUserIdAndOriginalFileName(String userId, String originalFileName) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<UserFile>()
                .eq(UserFile::getUserId, userId)
                .eq(UserFile::getOriginalFileName, originalFileName)));
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
