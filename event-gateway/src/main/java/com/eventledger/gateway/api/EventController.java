package com.eventledger.gateway.api;

import java.util.List;

import com.eventledger.gateway.event.LedgerEventService;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/events")
public class EventController {

    private final LedgerEventService eventService;

    public EventController(LedgerEventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody SubmitEventRequest request) {
        SubmitEventResult result = eventService.submit(request);
        if (result.duplicate()) {
            return ResponseEntity.ok(result.event());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.event());
    }

    @GetMapping("/{eventId}")
    public EventResponse getById(@PathVariable String eventId) {
        return eventService.getById(eventId);
    }

    @GetMapping
    public List<EventResponse> getByAccount(
            @RequestParam
            @NotBlank(message = "account is required")
            @Size(max = 100, message = "account must not exceed 100 characters")
            String account) {
        return eventService.getByAccount(account);
    }
}
