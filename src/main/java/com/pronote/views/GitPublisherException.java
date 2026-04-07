package com.pronote.views;

public class GitPublisherException extends RuntimeException {
    public GitPublisherException(String message) {
        super(message);
    }
    public GitPublisherException(String message, Throwable cause) {
        super(message, cause);
    }
}
