package com.lobsterai.skillgateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PPT 解析错误响应。
 *
 * - code      错误码（PPT_TOO_LARGE / PPT_PARSE_ERROR / PPT_ENCRYPTED / PPT_UNSUPPORTED_TYPE / PPT_TIMEOUT）
 * - message   用户可读的错误描述
 * - maxBytes  仅 PPT_TOO_LARGE 携带
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PptParseErrorResponse {

    private String code;
    private String message;
    private Long maxBytes;

    public PptParseErrorResponse(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
