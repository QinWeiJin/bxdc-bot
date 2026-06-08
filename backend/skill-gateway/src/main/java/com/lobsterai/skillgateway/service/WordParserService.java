package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.exception.WordParseException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Word 文档纯文本解析服务。
 *
 * 支持 .docx（OOXML）和 .doc（旧二进制格式）。
 * 仅返回纯文本，应用 80KB 截断。
 */
@Service
public class WordParserService {

    private static final int TEXT_MAX_BYTES = 80 * 1024;

    public WordParseResult parse(byte[] fileBytes, String fileName) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new WordParseException("WORD_PARSE_ERROR", "文件为空");
        }

        String lower = fileName == null ? "" : fileName.toLowerCase();
        try {
            if (lower.endsWith(".docx")) {
                return parseDocx(fileBytes);
            } else if (lower.endsWith(".doc")) {
                return parseDoc(fileBytes);
            } else {
                throw new WordParseException("WORD_UNSUPPORTED_TYPE",
                        "不支持的 Word 格式：" + fileName);
            }
        } catch (WordParseException e) {
            throw e;
        } catch (POIXMLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("encrypt")) {
                throw new WordParseException("WORD_ENCRYPTED", "Word 文档已加密，无法解析", e);
            }
            throw new WordParseException("WORD_PARSE_ERROR", "Word 文档解析失败：" + e.getMessage(), e);
        } catch (IOException e) {
            throw new WordParseException("WORD_PARSE_ERROR", "Word 文档读取失败：" + e.getMessage(), e);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new WordParseException("WORD_PARSE_ERROR", "Word 文档解析失败：" + msg, e);
        }
    }

    private WordParseResult parseDocx(byte[] fileBytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
        try (XWPFDocument doc = new XWPFDocument(bais)) {
            XWPFWordExtractor extractor = new XWPFWordExtractor(doc);
            String text = extractor.getText();
            int pageCount = doc.getProperties().getExtendedProperties() != null
                    && doc.getProperties().getExtendedProperties().getUnderlyingProperties() != null
                    ? doc.getProperties().getExtendedProperties().getUnderlyingProperties().getPages() : 1;
            return truncate(text, pageCount);
        }
    }

    private WordParseResult parseDoc(byte[] fileBytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
        try (HWPFDocument doc = new HWPFDocument(bais)) {
            WordExtractor extractor = new WordExtractor(doc);
            String text = extractor.getText();
            return truncate(text, doc.getSummaryInformation() != null
                    ? Math.max(1, doc.getSummaryInformation().getPageCount()) : 1);
        } catch (RuntimeException e) {
            // .doc 文件实际可能是 OOXML（.docx）格式，fallback 尝试 docx 解析
            try {
                return parseDocx(fileBytes);
            } catch (RuntimeException ex) {
                // 两种二进制解析都失败，fallback 尝试按纯文本读取
                try {
                    String text = new String(fileBytes, StandardCharsets.UTF_8);
                    return truncate(text, 1);
                } catch (RuntimeException ex2) {
                    // 三种都失败，抛原始错误
                    throw e;
                }
            }
        }
    }

    private WordParseResult truncate(String text, int pageCount) {
        if (text == null) {
            return new WordParseResult("", pageCount);
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= TEXT_MAX_BYTES) {
            return new WordParseResult(text, pageCount);
        }
        int maxChars = TEXT_MAX_BYTES;
        String truncated = text.substring(0, Math.min(maxChars, text.length()));
        int originalKB = (int) Math.ceil(bytes.length / 1024.0);
        truncated = truncated + "\n... [内容已截断，原 " + originalKB + " KB]";
        return new WordParseResult(truncated, pageCount);
    }

    public static class WordParseResult {
        public final String text;
        public final int pageCount;

        public WordParseResult(String text, int pageCount) {
            this.text = text;
            this.pageCount = pageCount;
        }
    }
}
