package com.pagos.payments.persistence;

import com.pagos.payments.domain.PaymentStatus;
import com.pagos.payments.domain.TransitionSource;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Historial de transiciones de solo inserción: permite reconstruir el ciclo de vida. */
@Repository
public class HistoryRepository {

    /** Una transición del ciclo de vida, tal como quedó registrada. */
    public record Entry(String fromStatus, String toStatus, String source, String correlationId, String detail,
                        Instant occurredAt) {
    }

    private final JdbcClient jdbc;

    public HistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String attemptId, String orderId, PaymentStatus from, PaymentStatus to,
                       TransitionSource source, Long providerEventRef, String correlationId, String detail) {
        jdbc.sql("""
                INSERT INTO payment_status_history
                  (attempt_id, order_id, from_status, to_status, source, provider_event_ref, correlation_id, detail)
                VALUES (:attemptId, :orderId, :from, :to, :source, :eventRef, :correlationId, :detail)
                """)
                .param("attemptId", attemptId).param("orderId", orderId)
                .param("from", from == null ? null : from.name()).param("to", to.name())
                .param("source", source.name()).param("eventRef", providerEventRef)
                .param("correlationId", correlationId).param("detail", detail)
                .update();
    }

    public List<Entry> findByAttempt(String attemptId) {
        return jdbc.sql("""
                SELECT from_status, to_status, source, correlation_id, detail, occurred_at
                  FROM payment_status_history WHERE attempt_id = :id ORDER BY id
                """)
                .param("id", attemptId)
                .query((rs, n) -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), Db.toInstant(rs.getTimestamp(6))))
                .list();
    }

    public int countForAttempt(String attemptId) {
        return jdbc.sql("SELECT COUNT(*) FROM payment_status_history WHERE attempt_id = :id")
                .param("id", attemptId).query(Integer.class).single();
    }
}
