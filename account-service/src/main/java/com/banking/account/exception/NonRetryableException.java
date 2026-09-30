package com.banking.account.exception;

public class NonRetryableException extends RuntimeException {
    public NonRetryableException(Throwable cause) {
        super(cause.getMessage(), cause);
    }
}