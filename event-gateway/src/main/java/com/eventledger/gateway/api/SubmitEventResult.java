package com.eventledger.gateway.api;

public record SubmitEventResult(
        EventResponse event,
        boolean duplicate) {
}
