package com.eventledger.gateway.client;

import org.springframework.http.HttpStatusCode;

public class AccountServiceRejectedException extends RuntimeException {

    private final HttpStatusCode status;

    public AccountServiceRejectedException(HttpStatusCode status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatusCode getStatus() {
        return status;
    }
}
