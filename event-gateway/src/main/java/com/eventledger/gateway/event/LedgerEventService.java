package com.eventledger.gateway.event;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eventledger.gateway.api.EventResponse;
import com.eventledger.gateway.api.SubmitEventRequest;
import com.eventledger.gateway.api.SubmitEventResult;
import com.eventledger.gateway.client.AccountServiceClient;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class LedgerEventService {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final LedgerEventRepository repository;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Counter acceptedEvents;
    private final Counter duplicateEvents;

    @Autowired
    public LedgerEventService(
            LedgerEventRepository repository,
            AccountServiceClient accountServiceClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this(repository, accountServiceClient, objectMapper, Clock.systemUTC(), meterRegistry);
    }

    LedgerEventService(
            LedgerEventRepository repository,
            AccountServiceClient accountServiceClient,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.accountServiceClient = accountServiceClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.acceptedEvents = Counter.builder("event_ledger_gateway_submissions")
                .description("Event submissions processed by the Gateway")
                .tag("outcome", "accepted")
                .register(meterRegistry);
        this.duplicateEvents = Counter.builder("event_ledger_gateway_submissions")
                .description("Event submissions processed by the Gateway")
                .tag("outcome", "duplicate")
                .register(meterRegistry);
    }

    @Transactional
    public synchronized SubmitEventResult submit(SubmitEventRequest request) {
        LedgerEvent existing = repository.findById(request.eventId()).orElse(null);
        if (existing != null) {
            duplicateEvents.increment();
            return new SubmitEventResult(toResponse(existing), true);
        }

        accountServiceClient.applyTransaction(request);

        LedgerEvent event = new LedgerEvent(
                request.eventId(),
                request.accountId(),
                request.type(),
                request.amount(),
                request.currency(),
                request.eventTimestamp(),
                serializeMetadata(request.metadata()),
                Instant.now(clock));

        LedgerEvent savedEvent = repository.saveAndFlush(event);
        acceptedEvents.increment();
        return new SubmitEventResult(toResponse(savedEvent), false);
    }

    @Transactional(readOnly = true)
    public EventResponse getById(String eventId) {
        return repository.findById(eventId)
                .map(this::toResponse)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getByAccount(String accountId) {
        return repository.findByAccountIdOrderByEventTimestampAscEventIdAsc(accountId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private EventResponse toResponse(LedgerEvent event) {
        return new EventResponse(
                event.getEventId(),
                event.getAccountId(),
                event.getType(),
                event.getAmount(),
                event.getCurrency(),
                event.getEventTimestamp(),
                deserializeMetadata(event.getMetadataJson()));
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
            throw new IllegalStateException("Stored event metadata is invalid", exception);
        }
    }
}
