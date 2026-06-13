package com.eventledger.gateway.api;

import com.eventledger.gateway.client.AccountBalanceResponse;
import com.eventledger.gateway.client.AccountServiceClient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/accounts")
public class AccountQueryController {

    private final AccountServiceClient accountServiceClient;

    public AccountQueryController(AccountServiceClient accountServiceClient) {
        this.accountServiceClient = accountServiceClient;
    }

    @GetMapping("/{accountId}/balance")
    public AccountBalanceResponse getBalance(
            @PathVariable
            @NotBlank(message = "accountId is required")
            @Size(max = 100, message = "accountId must not exceed 100 characters")
            String accountId) {
        return accountServiceClient.getBalance(accountId);
    }
}
