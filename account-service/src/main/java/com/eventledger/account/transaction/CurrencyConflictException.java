package com.eventledger.account.transaction;

public class CurrencyConflictException extends RuntimeException {

    public CurrencyConflictException(String accountId, String expectedCurrency, String suppliedCurrency) {
        super("Account '%s' uses currency %s; received %s"
                .formatted(accountId, expectedCurrency, suppliedCurrency));
    }
}
