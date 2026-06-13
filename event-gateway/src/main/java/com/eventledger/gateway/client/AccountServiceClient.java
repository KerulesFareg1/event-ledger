package com.eventledger.gateway.client;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import com.eventledger.gateway.api.SubmitEventRequest;
import com.eventledger.gateway.trace.TraceContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class AccountServiceClient {

    private static final TypeReference<Map<String, Object>> ERROR_BODY_TYPE = new TypeReference<>() {
    };

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AccountServiceClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${account-service.base-url}") String accountServiceBaseUrl,
            @Value("${account-service.resilience.connect-timeout}") Duration connectTimeout,
            @Value("${account-service.resilience.read-timeout}") Duration readTimeout,
            @Value("${account-service.resilience.max-attempts}") int maxAttempts,
            @Value("${account-service.resilience.initial-backoff}") Duration initialBackoff) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder
                .baseUrl(accountServiceBaseUrl)
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
    }

    private final int maxAttempts;
    private final Duration initialBackoff;

    public void applyTransaction(SubmitEventRequest event) {
        AccountTransactionRequest request = new AccountTransactionRequest(
                event.eventId(),
                event.type(),
                event.amount(),
                event.currency(),
                event.eventTimestamp(),
                event.metadata());

        execute(() -> {
            restClient.post()
                    .uri("/accounts/{accountId}/transactions", event.accountId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(TraceContext.HEADER_NAME, TraceContext.currentTraceId())
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    public AccountBalanceResponse getBalance(String accountId) {
        return execute(() -> restClient.get()
                .uri("/accounts/{accountId}/balance", accountId)
                .header(TraceContext.HEADER_NAME, TraceContext.currentTraceId())
                .retrieve()
                .body(AccountBalanceResponse.class));
    }

    private <T> T execute(Supplier<T> request) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return request.get();
            } catch (RestClientResponseException exception) {
                if (!exception.getStatusCode().is5xxServerError()) {
                    throw new AccountServiceRejectedException(
                            exception.getStatusCode(),
                            extractMessage(exception.getResponseBodyAsString()));
                }
                lastFailure = exception;
            } catch (ResourceAccessException exception) {
                lastFailure = exception;
            }

            if (attempt < maxAttempts) {
                backOff(attempt);
            }
        }

        throw new AccountServiceUnavailableException(
                "Account Service is temporarily unavailable",
                lastFailure);
    }

    private void backOff(int completedAttempt) {
        long delayMillis = initialBackoff.toMillis() * (1L << (completedAttempt - 1));
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AccountServiceUnavailableException(
                    "Account Service call was interrupted",
                    exception);
        }
    }

    private String extractMessage(String responseBody) {
        try {
            Object message = objectMapper.readValue(responseBody, ERROR_BODY_TYPE).get("message");
            return message == null ? "Account Service rejected the transaction" : message.toString();
        } catch (JacksonException exception) {
            return "Account Service rejected the transaction";
        }
    }
}
