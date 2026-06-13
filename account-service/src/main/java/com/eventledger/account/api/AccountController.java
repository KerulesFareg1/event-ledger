package com.eventledger.account.api;

import com.eventledger.account.transaction.AccountTransactionService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountTransactionService transactionService;

    public AccountController(AccountTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> applyTransaction(
            @PathVariable
            @NotBlank(message = "accountId is required")
            @Size(max = 100, message = "accountId must not exceed 100 characters")
            String accountId,
            @Valid @RequestBody ApplyTransactionRequest request) {
        ApplyTransactionResult result = transactionService.apply(accountId, request);
        if (result.duplicate()) {
            return ResponseEntity.ok(result.transaction());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.transaction());
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse getBalance(@PathVariable String accountId) {
        return transactionService.getBalance(accountId);
    }

    @GetMapping("/{accountId}")
    public AccountDetailsResponse getAccount(@PathVariable String accountId) {
        return transactionService.getAccountDetails(accountId);
    }
}
