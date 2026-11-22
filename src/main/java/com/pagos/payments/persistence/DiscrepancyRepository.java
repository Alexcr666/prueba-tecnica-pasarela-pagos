package com.pagos.payments.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DiscrepancyRepository {

    private final JdbcClient jdbc;

    public DiscrepancyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String runType, String attemptId, String providerPaymentId, String ourStatus,
                       String providerStatus, Long ourAmount, Long providerAmount) {
        jdbc.sql("""
                INSERT INTO reconciliation_discrepancy
                  (run_type, attempt_id, provider_payment_id, our_status, provider_status, our_amount, provider_amount)
                VALUES (:runType, :attemptId, :ppid, :ours, :theirs, :ourAmount, :theirAmount)
                """)
                .param("runType", runType).param("attemptId", attemptId).param("ppid", providerPaymentId)
                .param("ours", ourStatus).param("theirs", providerStatus)
                .param("ourAmount", ourAmount).param("theirAmount", providerAmount)
                .update();
    }
}
