package com.lobsterai.skillgateway.util;

import javax.servlet.http.HttpServletRequest;

/**
 * AAM 统一认证 Token 工具类。
 * <p>
 * 从 HTTP 请求头中提取 AAM 用户唯一标识。
 * agent-core 在调用 skill-gateway 文件 API 时，将当前登录用户的 AAM ID
 * 通过 X-User-Id 请求头传递过来。
 * </p>
 */
public class AamTokenUtil {

    /** 用户 ID 请求头名称 */
    public static final String USER_ID_HEADER = "X-User-Id";

    private AamTokenUtil() {
        // utility class
    }

    /**
     * 从 HTTP 请求中提取 AAM 用户 ID。
     *
     * @param request HTTP 请求
     * @return 用户 ID；如果未传递则返回 null
     */
    public static String extractUserId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String userId = request.getHeader(USER_ID_HEADER);
        if (userId != null) {
            userId = userId.trim();
            if (!userId.isEmpty()) {
                return userId;
            }
        }
        return null;
    }

    /**
     * 从 HTTP 请求中提取 AAM 用户 ID（必须存在）。
     *
     * @param request HTTP 请求
     * @return 用户 ID
     * @throws IllegalArgumentException 如果未传递用户 ID
     */
    public static String requireUserId(HttpServletRequest request) {
        String userId = extractUserId(request);
        if (userId == null) {
            throw new IllegalArgumentException("Missing required header: " + USER_ID_HEADER);
        }
        return userId;
    }

    /**
     * 校验请求用户是否有权操作指定 userId 的文件。
     * 用户只能操作自己的文件。
     *
     * @param requestUserId  请求中的用户 ID（从 header 提取）
     * @param targetUserId   目标文件的所属用户 ID
     * @return true 如果有权限
     */
    public static boolean canAccessUser(String requestUserId, String targetUserId) {
        if (requestUserId == null || targetUserId == null) {
            return false;
        }
        return requestUserId.equals(targetUserId);
    }

    /**
     * 校验并拒绝跨用户访问。
     *
     * @param requestUserId 请求中的用户 ID
     * @param targetUserId  目标文件的所属用户 ID
     * @throws SecurityException 如果用户尝试访问其他用户的文件
     */
    public static void enforceUserAccess(String requestUserId, String targetUserId) {
        if (!canAccessUser(requestUserId, targetUserId)) {
            throw new SecurityException(
                    "Access denied: user '" + requestUserId + "' cannot access files of user '" + targetUserId + "'"
            );
        }
    }
}
