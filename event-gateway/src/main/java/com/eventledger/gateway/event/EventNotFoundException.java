package com.eventledger.gateway.event;

public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(String eventId) {
        super("Event '%s' was not found".formatted(eventId));
    }
}
