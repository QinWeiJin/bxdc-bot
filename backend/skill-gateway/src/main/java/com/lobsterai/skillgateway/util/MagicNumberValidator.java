package com.lobsterai.skillgateway.util;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 文件魔数校验器。
 * <p>
 * 通过读取文件头部字节（魔数/Magic Number）来判定文件真实类型，
 * 而非依赖文件扩展名，防止伪装文件上传。
 * 不引入 Apache Tika，使用硬编码魔数表实现。
 * </p>
 *
 * <p>支持的魔数映射：</p>
 * <ul>
 *   <li>doc: OLE2 (D0 CF 11 E0 A1 B1 1A E1)</li>
 *   <li>docx: ZIP (50 4B 03 04) / OOXML</li>
 *   <li>xls: OLE2 (D0 CF 11 E0 A1 B1 1A E1)</li>
 *   <li>xlsx: ZIP (50 4B 03 04) / OOXML</li>
 *   <li>csv/txt/md/py: UTF-8/ASCII text (无固定魔数，由扩展名辅助判定)</li>
 * </ul>
 */
public class MagicNumberValidator {

    private MagicNumberValidator() {
        // utility class
    }

    /** OLE2 复合文档魔数（doc, xls） */
    private static final byte[] OLE2_MAGIC = new byte[]{
            (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1
    };

    /** ZIP/PK 压缩包魔数（docx, xlsx） */
    private static final byte[] ZIP_MAGIC = new byte[]{
            (byte) 0x50, (byte) 0x4B, (byte) 0x03, (byte) 0x04
    };

    /** 允许的文件扩展名（小写） */
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "doc", "docx", "xls", "xlsx", "csv", "txt", "md", "py"
    ));

    /** 扩展名 → 期望魔数类型的映射 */
    private static final Map<String, String> EXTENSION_TO_MAGIC_TYPE = new HashMap<>();

    static {
        EXTENSION_TO_MAGIC_TYPE.put("doc", "ole2");
        EXTENSION_TO_MAGIC_TYPE.put("xls", "ole2");
        EXTENSION_TO_MAGIC_TYPE.put("docx", "zip");
        EXTENSION_TO_MAGIC_TYPE.put("xlsx", "zip");
    }

    /**
     * 校验文件类型是否在允许列表中。
     * 对于二进制类型（doc/docx/xls/xlsx）使用魔数校验；
     * 对于文本类型（csv/txt/md/py）使用扩展名辅助判定。
     *
     * @param headerBytes 文件头部字节（至少 8 字节）
     * @param extension   文件扩展名（小写，不含点）
     * @return true 如果文件类型合法
     */
    public static boolean isAllowed(byte[] headerBytes, String extension) {
        if (headerBytes == null || headerBytes.length < 8) {
            return false;
        }
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
            return false;
        }
        String extLower = extension.toLowerCase();
        String expectedMagic = EXTENSION_TO_MAGIC_TYPE.get(extLower);
        if (expectedMagic == null) {
            // 文本类型（csv/txt/md/py）：无固定魔数，检查是否有可打印字符特征
            return isLikelyText(headerBytes);
        }
        String actualMagic = detectMagic(headerBytes);
        // OLE2 可用于 doc 和 xls，ZIP 可用于 docx 和 xlsx
        if ("ole2".equals(expectedMagic)) {
            return "ole2".equals(actualMagic);
        }
        if ("zip".equals(expectedMagic)) {
            return "zip".equals(actualMagic);
        }
        return false;
    }

    /**
     * 判断文件扩展名是否在允许列表中。
     *
     * @param extension 扩展名（小写，不含点）
     * @return true 如果扩展名允许
     */
    public static boolean isAllowedExtension(String extension) {
        return extension != null && ALLOWED_EXTENSIONS.contains(extension.toLowerCase());
    }

    /**
     * 获取允许的文件扩展名列表。
     *
     * @return 用顿号分隔的扩展名字符串（用于前端弹窗提示）
     */
    public static String getAllowedExtensionsText() {
        return "doc、docx、xls、xlsx、csv、txt、md";
    }

    /**
     * 获取允许的文件扩展名列表（含 py）。
     */
    public static String getAllowedExtensionsTextWithPy() {
        return "doc、docx、xls、xlsx、csv、txt、md、py";
    }

    /**
     * 检测文件魔数类型。
     */
    private static String detectMagic(byte[] header) {
        if (startsWith(header, OLE2_MAGIC)) {
            return "ole2";
        }
        if (startsWith(header, ZIP_MAGIC)) {
            return "zip";
        }
        return "unknown";
    }

    /**
     * 判断是否为文本文件（检查是否包含过多非可打印字符）。
     */
    private static boolean isLikelyText(byte[] header) {
        int nonPrintable = 0;
        int len = Math.min(header.length, 512);
        for (int i = 0; i < len; i++) {
            int b = header[i] & 0xFF;
            // UTF-8 BOM: EF BB BF
            if (i < 3 && b == 0xEF && (i == 0 || (i == 1 && (header[i - 1] & 0xFF) == 0xEF))) {
                continue;
            }
            if (b < 0x09 || (b > 0x0D && b < 0x20) || b == 0x7F) {
                nonPrintable++;
            }
        }
        // 允许少量控制字符（如换行、tab）
        return nonPrintable <= len * 0.1;
    }

    /**
     * 判断字节数组是否以指定前缀开头。
     */
    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
