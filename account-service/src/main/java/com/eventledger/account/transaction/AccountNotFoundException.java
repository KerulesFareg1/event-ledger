package com.eventledger.account.transaction;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountId) {
        super("Account '%s' was not found".formatted(accountId));
    }
}
