package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.entity.UserFile;
import com.lobsterai.skillgateway.mapper.UserFileMapper;
import com.lobsterai.skillgateway.util.AamTokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * 文件引用解析器（5.1.3）。
 * <p>
 * 将 fileRef（原始文件名或文件 ID）解析为 {@link UserFile} 实体。
 * 解析过程中自动校验用户权限（只能访问自己的文件）。
 * </p>
 *
 * <h3>支持的 fileRef 格式</h3>
 * <ul>
 *   <li>原始文件名：如 "报表.xlsx" — 按 userId + originalFileName 查 DB</li>
 *   <li>文件 ID 字符串：如 "123" — 按 id 查 DB</li>
 * </ul>
 */
@Service
public class FileRefResolver {

    private static final Logger log = LoggerFactory.getLogger(FileRefResolver.class);

    private final UserFileMapper userFileMapper;

    public FileRefResolver(UserFileMapper userFileMapper) {
        this.userFileMapper = userFileMapper;
    }

    /**
     * 从请求中提取 userId 并解析文件引用。
     *
     * @param request HTTP 请求（含 X-User-Id header）
     * @param fileRef 文件引用（原始文件名或文件 ID）
     * @return 解析后的 UserFile 实体
     * @throws IllegalArgumentException 文件未找到或无权访问
     */
    public UserFile resolve(HttpServletRequest request, String fileRef) {
        String userId = AamTokenUtil.requireUserId(request);
        return resolve(userId, fileRef);
    }

    /**
     * 根据 userId + fileRef 解析文件实体。
     *
     * @param userId  用户 ID
     * @param fileRef 文件引用
     * @return UserFile 实体
     * @throws IllegalArgumentException 文件未找到
     */
    public UserFile resolve(String userId, String fileRef) {
        if (fileRef == null || fileRef.trim().isEmpty()) {
            throw new IllegalArgumentException("fileRef is required");
        }
        String ref = fileRef.trim();

        // 尝试按数字 ID 解析
        UserFile userFile = tryResolveById(userId, ref);
        if (userFile != null) {
            return userFile;
        }

        // 按原始文件名解析
        Optional<UserFile> opt = userFileMapper.findByUserIdAndOriginalFileName(userId, ref);
        if (opt.isPresent()) {
            UserFile uf = opt.get();
            // 二次校验：确保文件属于当前用户
            AamTokenUtil.enforceUserAccess(userId, uf.getUserId());
            return uf;
        }

        throw new IllegalArgumentException(
                "File not found: '" + ref + "'. Use 'file_list' to see available files."
        );
    }

    /**
     * 检查文件是否属于指定用户（不抛出异常时的快速检查）。
     */
    public boolean isAccessible(String userId, String fileRef) {
        try {
            resolve(userId, fileRef);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ========== 内部方法 ==========

    private UserFile tryResolveById(String userId, String ref) {
        try {
            Long id = Long.valueOf(ref);
            UserFile uf = userFileMapper.selectById(id);
            if (uf != null && userId.equals(uf.getUserId())) {
                return uf;
            }
        } catch (NumberFormatException ignored) {
            // not a numeric ID, fall through
        }
        return null;
    }
}
