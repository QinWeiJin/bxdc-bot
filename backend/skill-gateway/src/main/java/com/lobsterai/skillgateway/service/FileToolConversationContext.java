package com.lobsterai.skillgateway.service;

import java.util.List;

/**
 * open spec: conversation-file-isolation
 * <p>
 * 通过 ThreadLocal 在执行上下文传递当前会话的 enabled_files 列表，
 * FileManageService 的 file_list/file_delete/file_detail/file_clear_all
 * 在执行时从 Context 读取并按 enabled_files 过滤。
 * </p>
 */
public final class FileToolConversationContext {

    private static final ThreadLocal<List<Long>> ENABLED_FILES = new ThreadLocal<List<Long>>();

    private FileToolConversationContext() {}

    public static void set(List<Long> ids) {
        ENABLED_FILES.set(ids);
    }

    public static List<Long> get() {
        return ENABLED_FILES.get();
    }

    public static void clear() {
        ENABLED_FILES.remove();
    }
}
