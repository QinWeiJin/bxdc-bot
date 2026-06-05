package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.exception.PptParseException;
import org.apache.poi.hslf.usermodel.*;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.xslf.extractor.XSLFExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

/**
 * PPT 纯文本解析服务。
 *
 * 支持：
 * - .pptx（新格式，OOXML）→ XMLSlideShow + XSLFExtractor
 * - .ppt  （旧格式，二进制）→ HSLFSlideShow + 手动遍历提取
 *
 * 行为：
 * - 仅返回纯文本
 * - 应用 80KB 截断（与前端 PARSED_TEXT_MAX_BYTES 对齐）
 * - 异常统一包装为 PptParseException
 */
@Service
public class PptParserService {

    /** 与前端 PARSED_TEXT_MAX_BYTES 一致 */
    private static final int TEXT_MAX_BYTES = 80 * 1024;

    /**
     * 解析 PPT 文件字节，返回纯文本和 slide 数。
     *
     * @param fileBytes PPT 文件字节
     * @param fileName  文件名（用于分派 .ppt vs .pptx）
     * @return 解析结果
     * @throws PptParseException 解析失败 / 加密 / 损坏
     */
    public PptParseResult parse(byte[] fileBytes, String fileName) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new PptParseException("PPT_PARSE_ERROR", "文件为空");
        }

        String lower = fileName == null ? "" : fileName.toLowerCase();
        try {
            if (lower.endsWith(".pptx")) {
                return parsePptx(fileBytes);
            } else if (lower.endsWith(".ppt")) {
                return parsePpt(fileBytes);
            } else {
                throw new PptParseException("PPT_UNSUPPORTED_TYPE",
                        "不支持的 PPT 格式：" + fileName);
            }
        } catch (PptParseException e) {
            throw e;
        } catch (POIXMLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("encrypt")) {
                throw new PptParseException("PPT_ENCRYPTED",
                        "PPT 文件已加密，无法解析", e);
            }
            throw new PptParseException("PPT_PARSE_ERROR",
                    "PPTX 文件解析失败：" + e.getMessage(), e);
        } catch (IOException e) {
            throw new PptParseException("PPT_PARSE_ERROR",
                    "PPT 文件读取失败：" + e.getMessage(), e);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (msg.toLowerCase().contains("encrypt")) {
                throw new PptParseException("PPT_ENCRYPTED",
                        "PPT 文件已加密，无法解析", e);
            }
            throw new PptParseException("PPT_PARSE_ERROR",
                    "PPT 解析失败：" + msg, e);
        }
    }

    private PptParseResult parsePptx(byte[] fileBytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
        try (XMLSlideShow ppt = new XMLSlideShow(bais)) {
            XSLFExtractor extractor = new XSLFExtractor(ppt);
            String text = extractor.getText();
            int slideCount = ppt.getSlides().size();
            return truncate(text, slideCount);
        }
    }

    private PptParseResult parsePpt(byte[] fileBytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
        try (HSLFSlideShow ppt = new HSLFSlideShow(bais)) {
            StringBuilder sb = new StringBuilder();
            List<HSLFSlide> slides = ppt.getSlides();
            
            for (HSLFSlide slide : slides) {
                // 提取 slide 中的所有文本
                List<HSLFShape> shapes = slide.getShapes();
                for (HSLFShape shape : shapes) {
                    if (shape instanceof HSLFTextShape) {
                        HSLFTextShape textShape = (HSLFTextShape) shape;
                        String text = textShape.getText();
                        if (text != null && !text.isEmpty()) {
                            sb.append(text).append("\n");
                        }
                    }
                }
                sb.append("\n"); // slide 之间空一行
            }
            
            return truncate(sb.toString(), slides.size());
        }
    }

    /**
     * 应用 80KB 字节截断，UTF-8 编码后超过 TEXT_MAX_BYTES 追加截断标记。
     */
    private PptParseResult truncate(String text, int slideCount) {
        if (text == null) {
            return new PptParseResult("", slideCount);
        }
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= TEXT_MAX_BYTES) {
            return new PptParseResult(text, slideCount);
        }
        int maxChars = TEXT_MAX_BYTES;
        String truncated = text.substring(0, Math.min(maxChars, text.length()));
        int originalKB = (int) Math.ceil(bytes.length / 1024.0);
        truncated = truncated + "\n... [内容已截断，原 " + originalKB + " KB]";
        return new PptParseResult(truncated, slideCount);
    }

    /** 解析结果 POJO */
    public static class PptParseResult {
        public final String text;
        public final int slideCount;

        public PptParseResult(String text, int slideCount) {
            this.text = text;
            this.slideCount = slideCount;
        }
    }
}