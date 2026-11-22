package com.pagos.payments.persistence;

import static com.pagos.payments.persistence.Db.NOW;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Inbox de webhooks. {@code UNIQUE (provider, provider_event_id)} deduplica las entregas. */
@Repository
public class ProviderEventRepository {

    public record NewEvent(String provider, String providerEventId, String merchantReference,
                           String providerPaymentId, String eventType, String reportedStatus,
                           Long reportedAmount, String reportedCurrency, String payload) {
    }

    public record EventRow(long id, String provider, String providerEventId, String merchantReference,
                           String providerPaymentId, String reportedStatus, Long reportedAmount,
                           String reportedCurrency, String processingStatus, int attempts) {
    }

    private final JdbcClient jdbc;
    private final Db db;

    public ProviderEventRepository(JdbcClient jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    /**
     * Inserta el evento. Devuelve vacío si es un duplicado: con dos entregas concurrentes, el segundo
     * INSERT espera el commit del primero y recibe ORA-00001.
     */
    public Optional<Long> insertIfAbsent(NewEvent e) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                    INSERT INTO provider_event
                      (provider, provider_event_id, merchant_reference, provider_payment_id, event_type,
                       reported_status, reported_amount, reported_currency, payload)
                    VALUES (:provider, :eventId, :reference, :ppid, :type, :status, :amount, :currency, :payload)
                    """)
                    .param("provider", e.provider()).param("eventId", e.providerEventId())
                    .param("reference", e.merchantReference()).param("ppid", e.providerPaymentId())
                    .param("type", e.eventType()).param("status", e.reportedStatus())
                    .param("amount", e.reportedAmount()).param("currency", e.reportedCurrency())
                    .param("payload", e.payload())
                    .update(keys, "id");
        } catch (DuplicateKeyException duplicate) {
            return Optional.empty();
        }
        return Optional.of(keys.getKey().longValue());
    }

    public List<Long> claim(int limit, int leaseSeconds) {
        List<String> ids = db.claimIds("""
                SELECT TO_CHAR(id) FROM provider_event
                 WHERE processing_status = 'RECEIVED' AND next_attempt_at <= %s
                   FOR UPDATE SKIP LOCKED
                """.formatted(NOW), Map.of(), limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Long> numericIds = ids.stream().map(Long::valueOf).toList();
        jdbc.sql("UPDATE provider_event SET next_attempt_at = %s + NUMTODSINTERVAL(:lease, 'SECOND') WHERE id IN (:ids)"
                        .formatted(NOW))
                .param("lease", leaseSeconds).param("ids", numericIds).update();
        return numericIds;
    }

    public Optional<EventRow> findById(long id) {
        return select(id, "");
    }

    /** Lee el evento bloqueando su fila hasta el fin de la transacción. */
    public Optional<EventRow> lockById(long id) {
        return select(id, " FOR UPDATE");
    }

    private Optional<EventRow> select(long id, String lockClause) {
        return jdbc.sql("""
                SELECT id, provider, provider_event_id, merchant_reference, provider_payment_id,
                       reported_status, reported_amount, reported_currency, processing_status, attempts
                  FROM provider_event WHERE id = :id
                """ + lockClause)
                .param("id", id)
                .query((rs, n) -> new EventRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), (Long) rs.getObject(7, Long.class),
                        rs.getString(8) == null ? null : rs.getString(8).trim(), rs.getString(9), rs.getInt(10)))
                .optional();
    }

    public void markDone(long id, String processingStatus) {
        jdbc.sql("""
                UPDATE provider_event SET processing_status = :st, processed_at = %s
                 WHERE id = :id AND processing_status = 'RECEIVED'
                """.formatted(NOW))
                .param("st", processingStatus).param("id", id).update();
    }

    /** Fallo al procesar: backoff de 1 s a 10 min; tras maxAttempts pasa a FAILED (DLQ en la BD). */
    public void recordFailure(long id, String error, int maxAttempts) {
        jdbc.sql("""
                UPDATE provider_event
                   SET attempts = attempts + 1,
                       last_error = SUBSTR(:error, 1, 400),
                       processing_status = CASE WHEN attempts + 1 >= :max THEN 'FAILED' ELSE processing_status END,
                       next_attempt_at = %s + NUMTODSINTERVAL(LEAST(POWER(2, LEAST(attempts, 10)), 600), 'SECOND')
                 WHERE id = :id
                """.formatted(NOW))
                .param("error", error).param("max", maxAttempts).param("id", id).update();
    }

    public String processingStatusOf(String provider, String providerEventId) {
        return jdbc.sql("SELECT processing_status FROM provider_event WHERE provider = :p AND provider_event_id = :e")
                .param("p", provider).param("e", providerEventId).query(String.class).single();
    }
}
