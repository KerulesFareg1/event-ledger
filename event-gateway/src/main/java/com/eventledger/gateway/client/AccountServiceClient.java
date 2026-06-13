package com.eventledger.gateway.client;

import java.util.Map;

import com.eventledger.gateway.api.SubmitEventRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
            @Value("${account-service.base-url}") String accountServiceBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(accountServiceBaseUrl).build();
        this.objectMapper = objectMapper;
    }

    public void applyTransaction(SubmitEventRequest event) {
        AccountTransactionRequest request = new AccountTransactionRequest(
                event.eventId(),
                event.type(),
                event.amount(),
                event.currency(),
                event.eventTimestamp(),
                event.metadata());

        try {
            restClient.post()
                    .uri("/accounts/{accountId}/transactions", event.accountId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw new AccountServiceRejectedException(
                    exception.getStatusCode(),
                    extractMessage(exception.getResponseBodyAsString()));
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
