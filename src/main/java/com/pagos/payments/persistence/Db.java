package com.pagos.payments.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Utilidades SQL compartidas.
 *
 * <p>El reloj es la base de datos ({@link #NOW}): todas las instancias comparan contra la misma
 * hora y no dependen de la sincronización de sus relojes.
 */
@Component
public class Db {

    /** Hora actual en UTC según Oracle. */
    public static final String NOW = "SYS_EXTRACT_UTC(SYSTIMESTAMP)";

    private final DataSource dataSource;

    public Db(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Reclama ids con {@code FOR UPDATE SKIP LOCKED} limitando el lote.
     *
     * <p>Oracle no admite {@code FETCH FIRST n ROWS} junto con {@code FOR UPDATE}; el lote se limita
     * con {@code maxRows} del cursor y con SKIP LOCKED las filas se bloquean a medida que se leen.
     * Debe llamarse dentro de una transacción: el JdbcTemplate usa la conexión ligada a ella.
     */
    public List<String> claimIds(String sql, Map<String, ?> params, int limit) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setMaxRows(limit);
        return new NamedParameterJdbcTemplate(template).queryForList(sql, params, String.class);
    }

    /** Hora actual según Oracle, para comparar contra columnas escritas con {@link #NOW}. */
    public Instant now() {
        return toInstant(new JdbcTemplate(dataSource).queryForObject("SELECT " + NOW + " FROM dual", Timestamp.class));
    }

    public static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}
