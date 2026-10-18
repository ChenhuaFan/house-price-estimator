package com.hsbc.market;

public class ModelServiceRejectedException extends RuntimeException {
    private final int statusCode;
    private final String code;

    public ModelServiceRejectedException(int statusCode, String code, String message) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
    }

    public int statusCode() {
        return statusCode;
    }

    public String code() {
        return code;
    }
}
