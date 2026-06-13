package com.eventledger.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.eventledger.gateway.event.LedgerEventRepository;
import com.eventledger.gateway.trace.TraceContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class EventGatewayApplicationTests {

    private static final AccountServiceStub ACCOUNT_SERVICE = AccountServiceStub.start();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LedgerEventRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void accountServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url", ACCOUNT_SERVICE::baseUrl);
        registry.add("account-service.resilience.connect-timeout", () -> "100ms");
        registry.add("account-service.resilience.read-timeout", () -> "250ms");
        registry.add("account-service.resilience.max-attempts", () -> "3");
        registry.add("account-service.resilience.initial-backoff", () -> "5ms");
    }

    @BeforeEach
    void resetState() {
        repository.deleteAll();
        ACCOUNT_SERVICE.reset();
    }

    @AfterAll
    static void stopAccountService() {
        ACCOUNT_SERVICE.stop();
    }

    @Test
    void healthReportsServiceAndDatabaseUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.service", is("event-gateway")))
                .andExpect(jsonPath("$.database.status", is("UP")))
                .andExpect(jsonPath("$.database.product", is("H2")))
                .andExpect(jsonPath("$.uptimeSeconds").isNumber());
    }

    @Test
    void submitsStoresAndForwardsAnEventToAccountService() throws Exception {
        mockMvc.perform(post("/events")
                .header(TraceContext.HEADER_NAME, "trace-client-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                {
                  "eventId": "evt-001",
                  "accountId": "acct-123",
                  "type": "CREDIT",
                  "amount": 150.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-15T14:02:11Z",
                  "metadata": {
                    "source": "mainframe-batch",
                    "batchId": "B-9042"
                  }
                }
                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(TraceContext.HEADER_NAME, "trace-client-001"))
                .andExpect(jsonPath("$.eventId", is("evt-001")))
                .andExpect(jsonPath("$.metadata.batchId", is("B-9042")));

        assertThat(ACCOUNT_SERVICE.requestCount()).isEqualTo(1);
        assertThat(ACCOUNT_SERVICE.lastPath())
                .isEqualTo("/accounts/acct-123/transactions");
        assertThat(ACCOUNT_SERVICE.lastTraceId()).isEqualTo("trace-client-001");

        Map<String, Object> forwardedBody = objectMapper.readValue(
                ACCOUNT_SERVICE.lastBody(),
                new TypeReference<>() {
                });
        assertThat(forwardedBody)
                .containsEntry("eventId", "evt-001")
                .containsEntry("type", "CREDIT")
                .containsEntry("currency", "USD")
                .doesNotContainKey("accountId");

        mockMvc.perform(get("/events/evt-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is("acct-123")))
                .andExpect(jsonPath("$.amount", is(150.0)));
    }

    @Test
    void generatesAndReturnsTraceIdWhenClientDoesNotSupplyOne() throws Exception {
        String traceId = mockMvc.perform(get("/events").param("account", "acct-empty"))
                .andExpect(status().isOk())
                .andExpect(header().exists(TraceContext.HEADER_NAME))
                .andReturn()
                .getResponse()
                .getHeader(TraceContext.HEADER_NAME);

        assertThat(traceId).matches("[a-f0-9]{32}");
    }

    @Test
    void duplicateEventReturnsOriginalWithoutCallingAccountServiceAgain() throws Exception {
        submitEvent(event(
                "evt-duplicate", "acct-idempotent", "CREDIT", "25.00",
                "2026-05-15T12:00:00Z"))
                .andExpect(status().isCreated());

        submitEvent(event(
                "evt-duplicate", "acct-different", "DEBIT", "999.00",
                "2026-05-16T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is("acct-idempotent")))
                .andExpect(jsonPath("$.type", is("CREDIT")))
                .andExpect(jsonPath("$.amount", is(25.0)))
                .andExpect(jsonPath("$.eventTimestamp", is("2026-05-15T12:00:00Z")));

        assertThat(ACCOUNT_SERVICE.requestCount()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void listsEventsByOriginalTimestampRegardlessOfArrivalOrder() throws Exception {
        submitEvent(event(
                "evt-late", "acct-order", "CREDIT", "30.00",
                "2026-05-15T16:00:00Z"))
                .andExpect(status().isCreated());
        submitEvent(event(
                "evt-early", "acct-order", "DEBIT", "10.00",
                "2026-05-15T12:00:00Z"))
                .andExpect(status().isCreated());
        submitEvent(event(
                "evt-middle", "acct-order", "CREDIT", "20.00",
                "2026-05-15T14:00:00Z"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/events").param("account", "acct-order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath(
                        "$[*].eventId",
                        contains("evt-early", "evt-middle", "evt-late")));
    }

    @Test
    void accountQueryReturnsAnEmptyListWhenNoEventsExist() throws Exception {
        mockMvc.perform(get("/events").param("account", "acct-empty"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void rejectsInvalidFieldsWithoutCallingAccountService() throws Exception {
        submitEvent("""
                {
                  "eventId": "",
                  "accountId": "",
                  "type": "CREDIT",
                  "amount": 0,
                  "currency": "usd"
                }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Request validation failed")))
                .andExpect(jsonPath("$.fieldErrors.eventId", is("eventId is required")))
                .andExpect(jsonPath("$.fieldErrors.accountId", is("accountId is required")))
                .andExpect(jsonPath("$.fieldErrors.amount", is("amount must be greater than 0")))
                .andExpect(jsonPath(
                        "$.fieldErrors.currency",
                        is("currency must be a three-letter uppercase code")))
                .andExpect(jsonPath(
                        "$.fieldErrors.eventTimestamp",
                        is("eventTimestamp is required")));

        assertThat(ACCOUNT_SERVICE.requestCount()).isZero();
    }

    @Test
    void rejectsUnknownEventType() throws Exception {
        submitEvent(event(
                "evt-invalid", "acct-invalid", "TRANSFER", "10.00",
                "2026-05-15T12:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.message",
                        is("Request body is malformed or contains an unsupported event type")));

        assertThat(ACCOUNT_SERVICE.requestCount()).isZero();
    }

    @Test
    void doesNotStoreAnEventRejectedByAccountService() throws Exception {
        ACCOUNT_SERVICE.rejectNext(
                409,
                """
                {
                  "message": "Account 'acct-123' uses currency USD; received EUR"
                }
                """);

        submitEvent("""
                {
                  "eventId": "evt-rejected",
                  "accountId": "acct-123",
                  "type": "CREDIT",
                  "amount": 10.00,
                  "currency": "EUR",
                  "eventTimestamp": "2026-05-15T12:00:00Z"
                }
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath(
                        "$.message",
                        is("Account 'acct-123' uses currency USD; received EUR")));

        assertThat(ACCOUNT_SERVICE.requestCount()).isEqualTo(1);
        assertThat(repository.existsById("evt-rejected")).isFalse();
        mockMvc.perform(get("/events/evt-rejected"))
                .andExpect(status().isNotFound());
    }

    @Test
    void retriesTransientAccountServiceFailuresAndEventuallyStoresTheEvent() throws Exception {
        ACCOUNT_SERVICE.failNext(2, 503, """
                {
                  "message": "Account Service is warming up"
                }
                """);

        submitEvent(event(
                "evt-retry", "acct-retry", "CREDIT", "15.00",
                "2026-05-15T12:00:00Z"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId", is("evt-retry")));

        assertThat(ACCOUNT_SERVICE.requestCount()).isEqualTo(3);
        assertThat(repository.existsById("evt-retry")).isTrue();
    }

    @Test
    void returnsServiceUnavailableAfterRetriesWhileLocalEventReadsStillWork() throws Exception {
        submitEvent(event(
                "evt-existing", "acct-degraded", "CREDIT", "20.00",
                "2026-05-15T11:00:00Z"))
                .andExpect(status().isCreated());
        ACCOUNT_SERVICE.reset();
        ACCOUNT_SERVICE.failNext(3, 503, """
                {
                  "message": "Account Service is unavailable"
                }
                """);

        submitEvent(event(
                "evt-unavailable", "acct-degraded", "DEBIT", "5.00",
                "2026-05-15T12:00:00Z"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath(
                        "$.message",
                        is("Account Service is temporarily unavailable")));

        assertThat(ACCOUNT_SERVICE.requestCount()).isEqualTo(3);
        assertThat(repository.existsById("evt-unavailable")).isFalse();

        mockMvc.perform(get("/events/evt-existing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId", is("evt-existing")));
        mockMvc.perform(get("/events").param("account", "acct-degraded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].eventId", is("evt-existing")));
    }

    @Test
    void proxiesBalanceAndReturnsServiceUnavailableWhenAccountServiceFails() throws Exception {
        ACCOUNT_SERVICE.respondNext(200, """
                {
                  "accountId": "acct-balance",
                  "balance": 125.50,
                  "currency": "USD"
                }
                """);

        mockMvc.perform(get("/accounts/acct-balance/balance")
                        .header(TraceContext.HEADER_NAME, "trace-balance-001"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceContext.HEADER_NAME, "trace-balance-001"))
                .andExpect(jsonPath("$.accountId", is("acct-balance")))
                .andExpect(jsonPath("$.balance", is(125.5)))
                .andExpect(jsonPath("$.currency", is("USD")));

        assertThat(ACCOUNT_SERVICE.lastTraceId()).isEqualTo("trace-balance-001");

        ACCOUNT_SERVICE.reset();
        ACCOUNT_SERVICE.failNext(3, 503, """
                {
                  "message": "Account Service is unavailable"
                }
                """);

        mockMvc.perform(get("/accounts/acct-balance/balance"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath(
                        "$.message",
                        is("Account Service is temporarily unavailable")));

        assertThat(ACCOUNT_SERVICE.requestCount()).isEqualTo(3);
    }

    @Test
    void returnsNotFoundForUnknownEvent() throws Exception {
        mockMvc.perform(get("/events/missing-event"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Event 'missing-event' was not found")));
    }

    @Test
    void requiresAccountQueryParameter() throws Exception {
        mockMvc.perform(get("/events"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Query parameter 'account' is required")));
    }

    @Test
    void exposesActuatorHealthAndCustomSubmissionMetric() throws Exception {
        submitEvent(event(
                "evt-metric", "acct-metric", "CREDIT", "10.00",
                "2026-05-15T12:00:00Z"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.components.db.status", is("UP")));

        mockMvc.perform(get("/actuator/metrics/event_ledger_gateway_submissions")
                        .param("tag", "outcome:accepted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("event_ledger_gateway_submissions")))
                .andExpect(jsonPath("$.measurements[0].value").isNumber());
    }

    private ResultActions submitEvent(String body) throws Exception {
        return mockMvc.perform(post("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String event(
            String eventId,
            String accountId,
            String type,
            String amount,
            String timestamp) {
        return """
                {
                  "eventId": "%s",
                  "accountId": "%s",
                  "type": "%s",
                  "amount": %s,
                  "currency": "USD",
                  "eventTimestamp": "%s"
                }
                """.formatted(eventId, accountId, type, amount, timestamp);
    }

    private static final class AccountServiceStub {

        private final HttpServer server;
        private final AtomicInteger requestCount = new AtomicInteger();
        private final List<String> bodies = new ArrayList<>();
        private volatile String lastPath;
        private volatile String lastTraceId;
        private volatile int nextStatus = 201;
        private volatile String nextResponse = "{}";
        private volatile int failuresRemaining;
        private volatile int failureStatus = 503;
        private volatile String failureResponse = "{}";

        private AccountServiceStub(HttpServer server) {
            this.server = server;
        }

        static AccountServiceStub start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
                AccountServiceStub stub = new AccountServiceStub(server);
                server.createContext("/accounts", stub::handle);
                server.start();
                return stub;
            } catch (IOException exception) {
                throw new IllegalStateException("Could not start Account Service test stub", exception);
            }
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        synchronized void reset() {
            requestCount.set(0);
            bodies.clear();
            lastPath = null;
            lastTraceId = null;
            nextStatus = 201;
            nextResponse = "{}";
            failuresRemaining = 0;
            failureStatus = 503;
            failureResponse = "{}";
        }

        synchronized void rejectNext(int status, String response) {
            nextStatus = status;
            nextResponse = response;
        }

        synchronized void respondNext(int status, String response) {
            nextStatus = status;
            nextResponse = response;
        }

        synchronized void failNext(int count, int status, String response) {
            failuresRemaining = count;
            failureStatus = status;
            failureResponse = response;
        }

        int requestCount() {
            return requestCount.get();
        }

        String lastPath() {
            return lastPath;
        }

        String lastTraceId() {
            return lastTraceId;
        }

        synchronized String lastBody() {
            return bodies.getLast();
        }

        void stop() {
            server.stop(0);
        }

        private synchronized void handle(HttpExchange exchange) throws IOException {
            requestCount.incrementAndGet();
            lastPath = exchange.getRequestURI().getPath();
            lastTraceId = exchange.getRequestHeaders().getFirst(TraceContext.HEADER_NAME);
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            int responseStatus = nextStatus;
            String responseBody = nextResponse;
            if (failuresRemaining > 0) {
                failuresRemaining--;
                responseStatus = failureStatus;
                responseBody = failureResponse;
            }

            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();

            nextStatus = 201;
            nextResponse = "{}";
        }
    }
}
