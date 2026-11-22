package com.pagos.payments.persistence;

import static com.pagos.payments.persistence.Db.NOW;

import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Idempotencia de la API del cliente.
 *
 * <p>El registro guarda el id del recurso creado. Una petición repetida devuelve la representación
 * actual de ese recurso, así la respuesta refleja el estado más reciente.
 */
@Repository
public class IdempotencyRepository {

    public record IdempotencyRow(String requestHash, String resourceId) {
    }

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserta la clave dentro de la transacción en curso. Con dos peticiones concurrentes con la
     * misma clave, la segunda espera el commit de la primera y recibe ORA-00001 → false.
     * Oracle revierte solo la sentencia fallida, así que la transacción sigue siendo usable.
     */
    public boolean tryInsert(String scope, String key, String requestHash, String resourceId) {
        try {
            jdbc.sql("""
                    INSERT INTO idempotency_record (scope, idem_key, request_hash, resource_id, expires_at)
                    VALUES (:scope, :key, :hash, :resourceId, %s + INTERVAL '24' HOUR)
                    """.formatted(NOW))
                    .param("scope", scope).param("key", key).param("hash", requestHash)
                    .param("resourceId", resourceId)
                    .update();
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    public Optional<IdempotencyRow> find(String scope, String key) {
        return jdbc.sql("SELECT request_hash, resource_id FROM idempotency_record WHERE scope = :scope AND idem_key = :key")
                .param("scope", scope).param("key", key)
                .query((rs, n) -> new IdempotencyRow(rs.getString(1), rs.getString(2)))
                .optional();
    }
}
