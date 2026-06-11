package com.lobsterai.skillgateway.service.parser;

import com.lobsterai.skillgateway.dto.FileParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * TXT / Markdown 文件解析器。
 * <p>
 * 从文件头部提取前 500 个字符作为内容预览。
 * 自动检测 UTF-8 / GBK 编码。
 * </p>
 */
@Component
public class TxtMdParser implements FileParser {

    private static final Logger log = LoggerFactory.getLogger(TxtMdParser.class);
    private static final int PREVIEW_CHARS = 500;

    @Override
    public String supportedType() {
        return "txt"; // also handles "md" via router
    }

    @Override
    public FileParseResult parse(byte[] fileBytes, String fileName) throws Exception {
        String text = decodeText(fileBytes);

        FileParseResult result = new FileParseResult();
        String ext = extractExtension(fileName);
        if ("md".equals(ext)) {
            result.setFileType("md");
        } else {
            result.setFileType("txt");
        }

        String[] lines = text.split("\\r?\\n", -1);
        result.setLineCount(lines.length);

        String preview = text.length() > PREVIEW_CHARS ? text.substring(0, PREVIEW_CHARS) : text;
        result.setContentPreview(preview.trim());

        return result;
    }

    /**
     * 尝试 UTF-8，失败则用 GBK 兜底。
     */
    private String decodeText(byte[] bytes) {
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Exception e2) {
                return new String(bytes, Charset.defaultCharset());
            }
        }
    }

    private String extractExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }
}
