package com.bocbang.backend.runtime;

/** Every recognition slot is taken; the caller should retry shortly. */
public class CapacityExceededException extends RuntimeException {

    public CapacityExceededException(String message) {
        super(message);
    }
}
