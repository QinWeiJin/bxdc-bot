package com.lobsterai.skillgateway.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Word 解析成功响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WordParseResponse {
    private String text;
    private int pageCount;
}
