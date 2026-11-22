package com.pagos.payments.application;

import static com.pagos.payments.persistence.Db.NOW;

import com.pagos.payments.domain.PaymentStateMachine;
import com.pagos.payments.domain.PaymentStatus;
import com.pagos.payments.domain.ProviderEvidence;
import com.pagos.payments.domain.TransitionResult;
import com.pagos.payments.domain.TransitionSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Conciliación diaria contra la liquidación del proveedor (sección 9.2 del diseño).
 *
 * <ol>
 *   <li>Carga el archivo del día en {@code settlement_line} (borra y recarga el día, en lote).</li>
 *   <li>Llama a {@code pkg_settlement.compare}, que cruza por conjuntos y registra las
 *       discrepancias en {@code reconciliation_discrepancy}. Pasos 1 y 2 van en una transacción.</li>
 *   <li>Corrige los "aprobados allá y no aquí" con la misma {@code transition(...)} que usan el
 *       webhook y la conciliación incremental, con origen {@code SETTLEMENT}. Una transacción
 *       corta por intento. Las diferencias de monto y los no liquidados se revisan a mano.</li>
 * </ol>
 */
@Service
public class SettlementReconciliationService {

    public static final String APPROVED_NOT_HERE = "APPROVED_NOT_HERE";
    public static final String NOT_SETTLED = "NOT_SETTLED";
    public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";

    private static final Logger log = LoggerFactory.getLogger(SettlementReconciliationService.class);
    private static final int BATCH_SIZE = 1_000;

    /** Resultado de una corrida. */
    public record Summary(LocalDate runDate, String provider, int linesLoaded, int autoFixed, int openDiscrepancies) {
    }

    private record PendingFix(long discrepancyId, String attemptId, String providerPaymentId,
                              Long providerAmount, String providerCurrency) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbc;
    private final PaymentTransitionService transitions;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;

    public SettlementReconciliationService(JdbcTemplate jdbcTemplate, JdbcClient jdbc,
                                           PaymentTransitionService transitions, TransactionTemplate tx,
                                           MeterRegistry metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbc = jdbc;
        this.transitions = transitions;
        this.tx = tx;
        this.metrics = metrics;
    }

    /**
     * Carga la liquidación de {@code runDate} y la compara. Se puede repetir para el mismo día:
     * la carga reemplaza las líneas del día y {@code compare} no duplica discrepancias abiertas.
     */
    public Summary loadAndCompare(LocalDate runDate, String provider, List<SettlementLine> lines) {
        tx.executeWithoutResult(status -> {
            replaceLines(runDate, provider, lines);
            compare(runDate, provider);
        });
        int fixed = fixApprovedNotHere(runDate, provider);
        int open = countOpen(runDate, provider);
        metrics.counter("payments.settlement.auto_fixed", "provider", provider).increment(fixed);
        log.info("Conciliación diaria run_date={} provider={} lineas={} corregidos={} discrepancias_abiertas={}",
                runDate, provider, lines.size(), fixed, open);
        return new Summary(runDate, provider, lines.size(), fixed, open);
    }

    /** Ejecuta solo el cruce PL/SQL sobre lo ya cargado en staging (debe llamarse en una transacción o con autocommit). */
    public void compare(LocalDate runDate, String provider) {
        jdbcTemplate.execute("{call pkg_settlement.compare(?, ?)}", (CallableStatementCallback<Void>) cs -> {
            cs.setDate(1, Date.valueOf(runDate));
            cs.setString(2, provider);
            cs.execute();
            return null;
        });
    }

    /**
     * Reemplaza las líneas del día. Una línea repetida (mismo provider_payment_id) viola la PK y
     * revierte la carga completa: un archivo mal formado no se concilia a medias.
     */
    private void replaceLines(LocalDate runDate, String provider, List<SettlementLine> lines) {
        Date day = Date.valueOf(runDate);
        jdbcTemplate.update("DELETE FROM settlement_line WHERE run_date = ? AND provider = ?", day, provider);
        jdbcTemplate.batchUpdate("""
                INSERT INTO settlement_line
                  (run_date, provider, provider_payment_id, merchant_reference, status, amount_minor, currency)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, lines, BATCH_SIZE, (ps, line) -> {
            ps.setDate(1, day);
            ps.setString(2, provider);
            ps.setString(3, line.providerPaymentId());
            ps.setString(4, line.merchantReference());
            ps.setString(5, line.status());
            ps.setLong(6, line.amountMinor());
            ps.setString(7, line.currency());
        });
    }

    /**
     * Caso (a): el proveedor liquidó como aprobado un pago que aquí no lo está. Solo se corrigen
     * los que la máquina de estados permite llevar a APPROVED; un DECLINED o EXPIRED aquí es una
     * contradicción entre terminales del proveedor y queda abierta para revisión manual.
     */
    private int fixApprovedNotHere(LocalDate runDate, String provider) {
        List<PendingFix> pending = jdbc.sql("""
                SELECT id, attempt_id, provider_payment_id, provider_amount, provider_currency
                  FROM reconciliation_discrepancy
                 WHERE run_type = 'SETTLEMENT' AND resolution = 'OPEN'
                   AND discrepancy_type = :type AND settlement_date = :day AND provider = :provider
                   AND attempt_id IS NOT NULL AND our_status IN (:fixable)
                 ORDER BY id
                """)
                .param("type", APPROVED_NOT_HERE).param("day", Date.valueOf(runDate)).param("provider", provider)
                .param("fixable", PaymentStateMachine.allowedSourceNamesOf(PaymentStatus.APPROVED))
                .query((rs, n) -> new PendingFix(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, Long.class), rs.getString(5) == null ? null : rs.getString(5).trim()))
                .list();

        int fixed = 0;
        for (PendingFix fix : pending) {
            try {
                Boolean applied = tx.execute(status -> applyFix(fix, runDate));
                if (Boolean.TRUE.equals(applied)) {
                    fixed++;
                }
            } catch (RuntimeException e) {
                // Un intento problemático no detiene al resto; su discrepancia sigue OPEN.
                metrics.counter("payments.settlement.fix_errors").increment();
                log.error("No se pudo corregir por liquidación attempt_id={} discrepancy_id={}",
                        fix.attemptId(), fix.discrepancyId(), e);
            }
        }
        return fixed;
    }

    /** Transición + cierre de la discrepancia en la misma transacción. */
    private boolean applyFix(PendingFix fix, LocalDate runDate) {
        TransitionResult result = transitions.transition(fix.attemptId(), PaymentStatus.APPROVED,
                TransitionSource.SETTLEMENT, null,
                new ProviderEvidence(fix.providerPaymentId(), fix.providerAmount(), fix.providerCurrency()),
                "settlement " + runDate);
        // Aplicada ahora o ya APPROVED (p. ej. llegó el webhook entre compare y la corrección).
        if (result.currentStatus() != PaymentStatus.APPROVED) {
            log.warn("Liquidación aprobada no aplicable attempt_id={} estado={}", fix.attemptId(),
                    result.currentStatus());
            return false;
        }
        jdbc.sql("""
                UPDATE reconciliation_discrepancy
                   SET resolution = 'AUTO_FIXED', resolved_at = %s
                 WHERE id = :id AND resolution = 'OPEN'
                """.formatted(NOW))
                .param("id", fix.discrepancyId()).update();
        return true;
    }

    private int countOpen(LocalDate runDate, String provider) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM reconciliation_discrepancy
                 WHERE run_type = 'SETTLEMENT' AND resolution = 'OPEN'
                   AND settlement_date = :day AND provider = :provider
                """)
                .param("day", Date.valueOf(runDate)).param("provider", provider)
                .query(Integer.class).single();
    }
}
