package com.pagos.payments.persistence;

import static com.pagos.payments.persistence.Db.NOW;

import com.pagos.payments.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    public record OrderRow(String id, String customerId, long amountMinor, String currency,
                           OrderStatus status, Instant expiresAt, Instant createdAt, boolean expired) {
    }

    /** {@code expired} se calcula con el reloj de la BD, igual en todas las instancias. */
    private static final String COLUMNS = "id, customer_id, amount_minor, currency, status, expires_at, created_at, "
            + "CASE WHEN expires_at < " + NOW + " THEN 1 ELSE 0 END AS expired";

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String customerId, long amountMinor, String currency, int ttlMinutes) {
        jdbc.sql("""
                INSERT INTO customer_order (id, customer_id, amount_minor, currency, status, expires_at)
                VALUES (:id, :customerId, :amount, :currency, 'AWAITING_PAYMENT',
                        %s + NUMTODSINTERVAL(:ttl, 'MINUTE'))
                """.formatted(NOW))
                .param("id", id).param("customerId", customerId).param("amount", amountMinor)
                .param("currency", currency).param("ttl", ttlMinutes)
                .update();
    }

    public Optional<OrderRow> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM customer_order WHERE id = :id")
                .param("id", id).query(this::map).optional();
    }

    /**
     * Bloquea la fila de la orden. Regla de concurrencia del diseño (8.2): toda transacción que
     * modifica una orden o sus intentos bloquea primero la orden y después el intento.
     */
    public Optional<OrderRow> lockForUpdate(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM customer_order WHERE id = :id FOR UPDATE")
                .param("id", id).query(this::map).optional();
    }

    /** UPDATE condicional: solo cambia si la orden está en uno de los estados esperados. */
    public int updateStatusIf(String id, List<OrderStatus> expected, OrderStatus target) {
        return jdbc.sql("""
                UPDATE customer_order
                   SET status = :target, version = version + 1, updated_at = %s
                 WHERE id = :id AND status IN (:expected)
                """.formatted(NOW))
                .param("target", target.name()).param("id", id)
                .param("expected", expected.stream().map(Enum::name).toList())
                .update();
    }

    /** Candidatas a expirar (sin bloqueo); cada una se re-evalúa bajo bloqueo. */
    public List<String> findExpirationCandidates(int limit) {
        return jdbc.sql("""
                SELECT id FROM customer_order
                 WHERE status = 'AWAITING_PAYMENT' AND expires_at < %s
                 ORDER BY expires_at
                 FETCH FIRST :limit ROWS ONLY
                """.formatted(NOW))
                .param("limit", limit).query(String.class).list();
    }

    private OrderRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new OrderRow(rs.getString("id"), rs.getString("customer_id"), rs.getLong("amount_minor"),
                rs.getString("currency").trim(), OrderStatus.valueOf(rs.getString("status")),
                Db.toInstant(rs.getTimestamp("expires_at")), Db.toInstant(rs.getTimestamp("created_at")),
                rs.getInt("expired") == 1);
    }
}
