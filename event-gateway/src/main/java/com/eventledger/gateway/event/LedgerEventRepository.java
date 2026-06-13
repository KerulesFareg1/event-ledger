package com.eventledger.gateway.event;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEventRepository extends JpaRepository<LedgerEvent, String> {

    List<LedgerEvent> findByAccountIdOrderByEventTimestampAscEventIdAsc(String accountId);
}
