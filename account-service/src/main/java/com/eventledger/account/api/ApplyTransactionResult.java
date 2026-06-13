package com.eventledger.account.api;

public record ApplyTransactionResult(
        TransactionResponse transaction,
        boolean duplicate) {
}
