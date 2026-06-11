package com.lobsterai.skillgateway.dto;

import com.lobsterai.skillgateway.dto.ExcelMetadata;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Excel 元数据提取响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExcelMetadataResponse {
    private ExcelMetadata metadata;
}