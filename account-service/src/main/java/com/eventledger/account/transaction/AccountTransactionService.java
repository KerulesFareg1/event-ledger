package com.eventledger.account.transaction;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eventledger.account.api.AccountDetailsResponse;
import com.eventledger.account.api.ApplyTransactionRequest;
import com.eventledger.account.api.ApplyTransactionResult;
import com.eventledger.account.api.BalanceResponse;
import com.eventledger.account.api.TransactionResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountTransactionService {

    private static final int RECENT_TRANSACTION_LIMIT = 20;
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final AccountTransactionRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AccountTransactionService(
            AccountTransactionRepository repository,
            ObjectMapper objectMapper) {
        this(repository, objectMapper, Clock.systemUTC());
    }

    AccountTransactionService(
            AccountTransactionRepository repository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public synchronized ApplyTransactionResult apply(String accountId, ApplyTransactionRequest request) {
        AccountTransaction existing = repository.findById(request.eventId()).orElse(null);
        if (existing != null) {
            if (!existing.getAccountId().equals(accountId)) {
                throw new EventAccountConflictException(
                        request.eventId(), existing.getAccountId(), accountId);
            }
            return new ApplyTransactionResult(toResponse(existing), true);
        }

        repository.findFirstByAccountIdOrderByEventTimestampAscEventIdAsc(accountId)
                .filter(transaction -> !transaction.getCurrency().equals(request.currency()))
                .ifPresent(transaction -> {
                    throw new CurrencyConflictException(
                            accountId, transaction.getCurrency(), request.currency());
                });

        AccountTransaction transaction = new AccountTransaction(
                request.eventId(),
                accountId,
                request.type(),
                request.amount(),
                request.currency(),
                request.eventTimestamp(),
                serializeMetadata(request.metadata()),
                Instant.now(clock));

        return new ApplyTransactionResult(toResponse(repository.saveAndFlush(transaction)), false);
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountId) {
        List<AccountTransaction> transactions = findAccountTransactions(accountId);
        return new BalanceResponse(
                accountId,
                calculateBalance(transactions),
                transactions.getFirst().getCurrency());
    }

    @Transactional(readOnly = true)
    public AccountDetailsResponse getAccountDetails(String accountId) {
        List<AccountTransaction> transactions = findAccountTransactions(accountId);
        int firstRecentIndex = Math.max(0, transactions.size() - RECENT_TRANSACTION_LIMIT);
        List<TransactionResponse> recentTransactions = transactions.subList(firstRecentIndex, transactions.size())
                .stream()
                .map(this::toResponse)
                .toList();

        return new AccountDetailsResponse(
                accountId,
                calculateBalance(transactions),
                transactions.getFirst().getCurrency(),
                recentTransactions);
    }

    private List<AccountTransaction> findAccountTransactions(String accountId) {
        List<AccountTransaction> transactions =
                repository.findByAccountIdOrderByEventTimestampAscEventIdAsc(accountId);
        if (transactions.isEmpty()) {
            throw new AccountNotFoundException(accountId);
        }
        return transactions;
    }

    private BigDecimal calculateBalance(List<AccountTransaction> transactions) {
        return transactions.stream()
                .map(transaction -> transaction.getType() == TransactionType.CREDIT
                        ? transaction.getAmount()
                        : transaction.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private TransactionResponse toResponse(AccountTransaction transaction) {
        return new TransactionResponse(
                transaction.getEventId(),
                transaction.getAccountId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getEventTimestamp(),
                deserializeMetadata(transaction.getMetadataJson()));
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("metadata must contain JSON-compatible values", exception);
        }
    }

    private Map<String, Object> deserializeMetadata(String metadataJson) {
        if (metadataJson == null) {
            return Collections.emptyMap();
        }
        try {
            return Collections.unmodifiableMap(
                    new LinkedHashMap<>(objectMapper.readValue(metadataJson, METADATA_TYPE)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored transaction metadata is invalid", exception);
        }
    }
}
