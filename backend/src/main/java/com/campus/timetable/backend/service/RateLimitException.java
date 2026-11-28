package com.campus.timetable.backend.service;

/**
 * 频率限制异常，映射为 HTTP 429 Too Many Requests。
 */
public class RateLimitException extends RuntimeException {
    public RateLimitException(String message) {
        super(message);
    }
}
