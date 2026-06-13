package com.eventledger.account;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eventledger.account.transaction.AccountTransactionRepository;
import com.eventledger.account.trace.TraceContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AccountServiceApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountTransactionRepository repository;

    @BeforeEach
    void clearTransactions() {
        repository.deleteAll();
    }

    @Test
    void healthReportsServiceAndDatabaseUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.service", is("account-service")))
                .andExpect(jsonPath("$.database.status", is("UP")))
                .andExpect(jsonPath("$.database.product", is("H2")))
                .andExpect(jsonPath("$.uptimeSeconds").isNumber());
    }

    @Test
    void appliesCreditsAndDebitsAndReturnsTheNetBalance() throws Exception {
        applyTransaction("acct-123", """
                {
                  "eventId": "evt-credit",
                  "type": "CREDIT",
                  "amount": 150.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-15T14:02:11Z",
                  "metadata": {"source": "mainframe-batch"}
                }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.metadata.source", is("mainframe-batch")));

        applyTransaction("acct-123", """
                {
                  "eventId": "evt-debit",
                  "type": "DEBIT",
                  "amount": 40.25,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-15T15:00:00Z"
                }
                """)
                .andExpect(status().isCreated());

        mockMvc.perform(get("/accounts/acct-123/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", is("acct-123")))
                .andExpect(jsonPath("$.balance", is(109.75)))
                .andExpect(jsonPath("$.currency", is("USD")));
    }

    @Test
    void returnsTransactionsInEventTimestampOrderRegardlessOfArrivalOrder() throws Exception {
        applyTransaction("acct-order", transaction(
                "evt-late", "CREDIT", "50.00", "2026-05-15T16:00:00Z"))
                .andExpect(status().isCreated());
        applyTransaction("acct-order", transaction(
                "evt-early", "DEBIT", "10.00", "2026-05-15T12:00:00Z"))
                .andExpect(status().isCreated());
        applyTransaction("acct-order", transaction(
                "evt-middle", "CREDIT", "20.00", "2026-05-15T14:00:00Z"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/accounts/acct-order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(60.0)))
                .andExpect(jsonPath("$.recentTransactions", hasSize(3)))
                .andExpect(jsonPath(
                        "$.recentTransactions[*].eventId",
                        contains("evt-early", "evt-middle", "evt-late")));
    }

    @Test
    void duplicateEventReturnsTheOriginalTransactionWithoutChangingBalance() throws Exception {
        applyTransaction("acct-idempotent", transaction(
                "evt-duplicate", "CREDIT", "25.00", "2026-05-15T12:00:00Z"))
                .andExpect(status().isCreated());

        applyTransaction("acct-idempotent", transaction(
                "evt-duplicate", "CREDIT", "999.00", "2026-05-16T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount", is(25.0)))
                .andExpect(jsonPath("$.eventTimestamp", is("2026-05-15T12:00:00Z")));

        mockMvc.perform(get("/accounts/acct-idempotent/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance", is(25.0)));
    }

    @Test
    void rejectsInvalidTransactionFieldsWithMeaningfulErrors() throws Exception {
        applyTransaction("acct-invalid", """
                {
                  "eventId": "",
                  "type": "CREDIT",
                  "amount": 0,
                  "currency": "usd"
                }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Request validation failed")))
                .andExpect(jsonPath("$.fieldErrors.eventId", is("eventId is required")))
                .andExpect(jsonPath("$.fieldErrors.amount", is("amount must be greater than 0")))
                .andExpect(jsonPath(
                        "$.fieldErrors.currency",
                        is("currency must be a three-letter uppercase code")))
                .andExpect(jsonPath(
                        "$.fieldErrors.eventTimestamp",
                        is("eventTimestamp is required")));
    }

    @Test
    void rejectsUnknownTransactionType() throws Exception {
        applyTransaction("acct-invalid-type", transaction(
                "evt-invalid", "TRANSFER", "10.00", "2026-05-15T12:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.message",
                        is("Request body is malformed or contains an unsupported transaction type")));
    }

    @Test
    void rejectsASecondCurrencyForAnExistingAccount() throws Exception {
        applyTransaction("acct-currency", transaction(
                "evt-usd", "CREDIT", "10.00", "2026-05-15T12:00:00Z"))
                .andExpect(status().isCreated());

        applyTransaction("acct-currency", """
                {
                  "eventId": "evt-eur",
                  "type": "CREDIT",
                  "amount": 10.00,
                  "currency": "EUR",
                  "eventTimestamp": "2026-05-15T13:00:00Z"
                }
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath(
                        "$.message",
                        is("Account 'acct-currency' uses currency USD; received EUR")));
    }

    @Test
    void rejectsReusingAnEventIdForAnotherAccount() throws Exception {
        applyTransaction("acct-original", transaction(
                "evt-shared", "CREDIT", "10.00", "2026-05-15T12:00:00Z"))
                .andExpect(status().isCreated());

        applyTransaction("acct-other", transaction(
                "evt-shared", "CREDIT", "10.00", "2026-05-15T12:00:00Z"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath(
                        "$.message",
                        is("Event 'evt-shared' was already applied to account 'acct-original', not 'acct-other'")));
    }

    @Test
    void returnsNotFoundForAnAccountWithoutTransactions() throws Exception {
        mockMvc.perform(get("/accounts/missing-account/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Account 'missing-account' was not found")));
    }

    @Test
    void preservesTraceIdAndExposesActuatorMetrics() throws Exception {
        mockMvc.perform(post("/accounts/{accountId}/transactions", "acct-trace")
                        .header(TraceContext.HEADER_NAME, "trace-gateway-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transaction(
                                "evt-trace", "CREDIT", "10.00",
                                "2026-05-15T12:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(header().string(TraceContext.HEADER_NAME, "trace-gateway-001"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status", is("UP")));

        mockMvc.perform(get("/actuator/metrics/event_ledger_account_transactions")
                        .param("tag", "outcome:applied"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("event_ledger_account_transactions")))
                .andExpect(jsonPath("$.measurements[0].value").isNumber());
    }

    private org.springframework.test.web.servlet.ResultActions applyTransaction(
            String accountId,
            String body) throws Exception {
        return mockMvc.perform(post("/accounts/{accountId}/transactions", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String transaction(
            String eventId,
            String type,
            String amount,
            String timestamp) {
        return """
                {
                  "eventId": "%s",
                  "type": "%s",
                  "amount": %s,
                  "currency": "USD",
                  "eventTimestamp": "%s"
                }
                """.formatted(eventId, type, amount, timestamp);
    }
}
