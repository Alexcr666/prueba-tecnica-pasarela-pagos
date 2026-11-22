package com.pagos.payments.persistence;

import static com.pagos.payments.persistence.Db.NOW;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Transactional Outbox: el evento se guarda en la misma transacción que el cambio de estado. */
@Repository
public class OutboxRepository {

    public record OutboxRow(String id, String aggregateType, String aggregateId, String eventType, String payload) {
    }

    private final JdbcClient jdbc;
    private final Db db;

    public OutboxRepository(JdbcClient jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    /** El id lo genera quien arma el sobre del evento: es el mismo eventId del JSON y el message_id AMQP. */
    public String add(String id, String aggregateType, String aggregateId, String eventType, String jsonPayload) {
        jdbc.sql("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (:id, :type, :aggId, :eventType, :payload)
                """)
                .param("id", id).param("type", aggregateType).param("aggId", aggregateId)
                .param("eventType", eventType).param("payload", jsonPayload)
                .update();
        return id;
    }

    /** Reclama eventos NEW con lease; si la instancia muere, el lease vence y otra los toma. */
    public List<OutboxRow> claim(int limit, int leaseSeconds) {
        List<String> ids = db.claimIds("""
                SELECT id FROM outbox_event
                 WHERE status = 'NEW' AND next_attempt_at <= %s
                   FOR UPDATE SKIP LOCKED
                """.formatted(NOW), Map.of(), limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        jdbc.sql("UPDATE outbox_event SET next_attempt_at = %s + NUMTODSINTERVAL(:lease, 'SECOND') WHERE id IN (:ids)"
                        .formatted(NOW))
                .param("lease", leaseSeconds).param("ids", ids).update();
        return jdbc.sql("SELECT id, aggregate_type, aggregate_id, event_type, payload FROM outbox_event WHERE id IN (:ids) ORDER BY id")
                .param("ids", ids)
                .query((rs, n) -> new OutboxRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)))
                .list();
    }

    public void markSent(String id) {
        jdbc.sql("UPDATE outbox_event SET status = 'SENT', sent_at = %s WHERE id = :id".formatted(NOW))
                .param("id", id).update();
    }

    /** Fallo al publicar: reintento con backoff exponencial, máximo 60 s. Nunca se descarta. */
    public void reschedule(String id) {
        jdbc.sql("""
                UPDATE outbox_event
                   SET attempts = attempts + 1,
                       next_attempt_at = %s + NUMTODSINTERVAL(LEAST(POWER(2, LEAST(attempts, 6)), 60), 'SECOND')
                 WHERE id = :id
                """.formatted(NOW))
                .param("id", id).update();
    }

    public int countByAggregateAndType(String aggregateId, String eventType) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = :id AND event_type = :type")
                .param("id", aggregateId).param("type", eventType).query(Integer.class).single();
    }
}
