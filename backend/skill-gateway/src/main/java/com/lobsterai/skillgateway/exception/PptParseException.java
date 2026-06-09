package com.lobsterai.skillgateway.exception;

/**
 * PPT 解析异常。携带错误码（code）用于 HTTP 状态码映射。
 *
 * 错误码（code）：
 * - PPT_TOO_LARGE      → 413 Payload Too Large
 * - PPT_PARSE_ERROR    → 400 Bad Request
 * - PPT_ENCRYPTED      → 400 Bad Request
 * - PPT_UNSUPPORTED_TYPE → 415 Unsupported Media Type
 * - PPT_TIMEOUT        → 504 Gateway Timeout
 */
public class PptParseException extends RuntimeException {

    private final String code;

    public PptParseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PptParseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
