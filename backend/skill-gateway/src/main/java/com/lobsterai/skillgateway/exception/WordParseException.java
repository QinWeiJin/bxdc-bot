package com.lobsterai.skillgateway.exception;

/**
 * Word 文档解析异常。
 */
public class WordParseException extends RuntimeException {

    private final String code;

    public WordParseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public WordParseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
