package com.codemind.review.domain.dag;

public class DagExecutionException extends Exception {

    public DagExecutionException(String message) {
        super(message);
    }

    public DagExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
