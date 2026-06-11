package com.lobsterai.skillgateway.service.parser;

import com.lobsterai.skillgateway.dto.FileParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Python 文件解析器。
 * <p>
 * 完整解析 .py 文件内容（不截断）。
 * 用户目录下最多 5 个 .py 文件，单文件不超过 10MB。
 * 数量和大小的校验由 FileParseService 在上层完成。
 * </p>
 */
@Component
public class PyParser implements FileParser {

    private static final Logger log = LoggerFactory.getLogger(PyParser.class);

    @Override
    public String supportedType() {
        return "py";
    }

    @Override
    public FileParseResult parse(byte[] fileBytes, String fileName) throws Exception {
        String text = decodeText(fileBytes);

        FileParseResult result = new FileParseResult();
        result.setFileType("py");

        String[] lines = text.split("\\r?\\n", -1);
        result.setLineCount(lines.length);
        // 全量内容不截断
        result.setFullContent(text);
        // 同时提供前 500 字预览
        result.setContentPreview(text.length() > 500 ? text.substring(0, 500) : text);

        return result;
    }

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
}
