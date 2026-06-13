package com.eventledger.account.trace;

public final class TraceContext {

    public static final String HEADER_NAME = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    private TraceContext() {
    }
}
