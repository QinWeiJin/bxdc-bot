package com.lobsterai.skillgateway.service.parser;

import com.lobsterai.skillgateway.dto.FileParseResult;

/**
 * 文件解析器接口。
 * <p>
 * 每种文件类型对应一个解析器实现，产出统一的 FileParseResult。
 * </p>
 */
public interface FileParser {

    /**
     * 解析文件内容。
     *
     * @param fileBytes 文件字节数组
     * @param fileName  文件名（用于识别扩展名）
     * @return 解析结果
     * @throws Exception 解析失败
     */
    FileParseResult parse(byte[] fileBytes, String fileName) throws Exception;

    /**
     * 返回此解析器支持的文件类型（扩展名小写），如 "docx", "xlsx", "txt"。
     */
    String supportedType();
}
