package com.eventledger.account.transaction;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, String> {

    List<AccountTransaction> findByAccountIdOrderByEventTimestampAscEventIdAsc(String accountId);

    Optional<AccountTransaction> findFirstByAccountIdOrderByEventTimestampAscEventIdAsc(String accountId);
}
