package com.lobsterai.skillgateway.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Excel 解析成功响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExcelParseResponse {
    private String text;
    private int sheetCount;
}
