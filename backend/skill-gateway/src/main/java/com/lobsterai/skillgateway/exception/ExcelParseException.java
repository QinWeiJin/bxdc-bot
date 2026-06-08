package com.lobsterai.skillgateway.exception;

/**
 * Excel 解析异常。
 */
public class ExcelParseException extends RuntimeException {

    private final String code;

    public ExcelParseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ExcelParseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
