package com.pagos.payments.persistence;

import static com.pagos.payments.persistence.Db.NOW;

import com.pagos.payments.domain.PaymentAttempt;
import com.pagos.payments.domain.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentAttemptRepository {

    private static final String COLUMNS = """
            id, order_id, attempt_no, provider, provider_payment_id, amount_minor, currency,
            status, status_reason, redirect_url, check_count, created_at""";

    private final JdbcClient jdbc;
    private final Db db;

    public PaymentAttemptRepository(JdbcClient jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    /**
     * Inserta el intento en CREATED. Si la orden ya tiene un intento activo, el índice único
     * {@code UX_ATTEMPT_ACTIVE} lanza {@code DuplicateKeyException} (regla R1).
     */
    public void insertCreated(String id, String orderId, String provider, long amountMinor, String currency) {
        jdbc.sql("""
                INSERT INTO payment_attempt (id, order_id, attempt_no, provider, amount_minor, currency, status)
                VALUES (:id, :orderId,
                        (SELECT NVL(MAX(attempt_no), 0) + 1 FROM payment_attempt WHERE order_id = :orderId),
                        :provider, :amount, :currency, 'CREATED')
                """)
                .param("id", id).param("orderId", orderId).param("provider", provider)
                .param("amount", amountMinor).param("currency", currency)
                .update();
    }

    public Optional<PaymentAttempt> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment_attempt WHERE id = :id")
                .param("id", id).query(this::map).optional();
    }

    public Optional<PaymentAttempt> findActiveByOrder(String orderId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM payment_attempt
                WHERE order_id = :orderId AND status IN ('CREATED','PENDING','UNKNOWN')""")
                .param("orderId", orderId).query(this::map).optional();
    }

    public Optional<PaymentAttempt> findByProviderPaymentId(String provider, String providerPaymentId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM payment_attempt
                WHERE provider = :provider AND provider_payment_id = :ppid""")
                .param("provider", provider).param("ppid", providerPaymentId).query(this::map).optional();
    }

    public boolean hasActiveAttempt(String orderId) {
        return findActiveByOrder(orderId).isPresent();
    }

    /**
     * Núcleo de la idempotencia de transiciones: solo cambia el estado si el actual está entre los
     * orígenes permitidos. Devuelve las filas afectadas (0 = duplicado u obsoleto).
     */
    public int transitionIf(String id, PaymentStatus target, List<String> allowedFrom, String reason) {
        if (allowedFrom.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                UPDATE payment_attempt
                   SET status = :target,
                       status_reason = NVL(:reason, status_reason),
                       -- UNKNOWN programa su primera consulta en la misma sentencia: si el proceso
                       -- muere justo después, el intento no queda sin conciliar.
                       next_check_at = CASE WHEN :target = 'UNKNOWN'
                                            THEN %1$s + NUMTODSINTERVAL(30, 'SECOND') ELSE next_check_at END,
                       version = version + 1,
                       updated_at = %1$s
                 WHERE id = :id AND status IN (:allowedFrom)
                """.formatted(NOW))
                .param("target", target.name()).param("reason", reason)
                .param("id", id).param("allowedFrom", allowedFrom)
                .update();
    }

    /**
     * Guarda los datos del proveedor aunque el estado ya haya avanzado (p. ej. el webhook llegó
     * antes que la respuesta de creación). No sobrescribe un provider_payment_id ya conocido.
     */
    public void attachProviderData(String id, String providerPaymentId, String redirectUrl) {
        jdbc.sql("""
                UPDATE payment_attempt
                   SET provider_payment_id = NVL(provider_payment_id, :ppid),
                       redirect_url = NVL(:redirectUrl, redirect_url),
                       updated_at = %s
                 WHERE id = :id
                """.formatted(NOW))
                .param("ppid", providerPaymentId).param("redirectUrl", redirectUrl).param("id", id)
                .update();
    }

    public void scheduleCheck(String id, int delaySeconds) {
        jdbc.sql("UPDATE payment_attempt SET next_check_at = %s + NUMTODSINTERVAL(:s, 'SECOND') WHERE id = :id"
                        .formatted(NOW))
                .param("s", delaySeconds).param("id", id).update();
    }

    /**
     * Reclama intentos para conciliar (sección 9.2) y les pone un lease con backoff exponencial.
     * Solo toca filas de intentos, nunca la orden, y no espera bloqueos (SKIP LOCKED): no puede
     * formar un ciclo con las transacciones que bloquean orden → intento.
     */
    public List<String> claimForReconciliation(int limit, int createdOrphanSeconds, int pendingStaleSeconds) {
        List<String> ids = db.claimIds("""
                SELECT id FROM payment_attempt
                 WHERE (status = 'UNKNOWN' AND (next_check_at IS NULL OR next_check_at <= %1$s))
                    OR (status = 'CREATED' AND created_at < %1$s - NUMTODSINTERVAL(:orphan, 'SECOND')
                        AND (next_check_at IS NULL OR next_check_at <= %1$s))
                    OR (status = 'PENDING' AND updated_at < %1$s - NUMTODSINTERVAL(:stale, 'SECOND')
                        AND (next_check_at IS NULL OR next_check_at <= %1$s))
                   FOR UPDATE SKIP LOCKED
                """.formatted(NOW), Map.of("orphan", createdOrphanSeconds, "stale", pendingStaleSeconds), limit);
        if (!ids.isEmpty()) {
            // Backoff: 30 s * 2^check_count, máximo 30 min entre consultas.
            jdbc.sql("""
                    UPDATE payment_attempt
                       SET check_count = check_count + 1,
                           next_check_at = %s + NUMTODSINTERVAL(LEAST(30 * POWER(2, LEAST(check_count, 6)), 1800), 'SECOND')
                     WHERE id IN (:ids)
                    """.formatted(NOW))
                    .param("ids", ids).update();
        }
        return ids;
    }

    private PaymentAttempt map(ResultSet rs, int n) throws SQLException {
        return new PaymentAttempt(rs.getString("id"), rs.getString("order_id"), rs.getInt("attempt_no"),
                rs.getString("provider"), rs.getString("provider_payment_id"), rs.getLong("amount_minor"),
                rs.getString("currency").trim(), PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("status_reason"), rs.getString("redirect_url"), rs.getInt("check_count"),
                Db.toInstant(rs.getTimestamp("created_at")));
    }
}
