package com.lobsterai.skillgateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Word 解析错误响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WordParseErrorResponse {
    private String code;
    private String message;
    private Long maxBytes;

    public WordParseErrorResponse(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
