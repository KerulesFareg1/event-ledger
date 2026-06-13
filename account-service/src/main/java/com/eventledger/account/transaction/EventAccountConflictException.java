package com.eventledger.account.transaction;

public class EventAccountConflictException extends RuntimeException {

    public EventAccountConflictException(String eventId, String originalAccountId, String suppliedAccountId) {
        super("Event '%s' was already applied to account '%s', not '%s'"
                .formatted(eventId, originalAccountId, suppliedAccountId));
    }
}
