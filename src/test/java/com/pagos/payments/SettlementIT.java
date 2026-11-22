package com.pagos.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.pagos.payments.application.EventPublisher;
import com.pagos.payments.application.SettlementLine;
import com.pagos.payments.application.SettlementReconciliationService;
import com.pagos.payments.application.SettlementReconciliationService.Summary;
import com.pagos.payments.domain.OrderStatus;
import com.pagos.payments.domain.PaymentAttempt;
import com.pagos.payments.domain.PaymentStatus;
import com.pagos.payments.persistence.OrderRepository;
import com.pagos.payments.persistence.OutboxRepository;
import com.pagos.payments.persistence.PaymentAttemptRepository;
import com.pagos.payments.support.UuidV7;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/**
 * Conciliación diaria contra la liquidación (sección 9.2): carga en staging, pkg_settlement.compare
 * y corrección automática de los aprobados en la liquidación. Oracle real con Testcontainers.
 *
 * <p>Cada prueba usa su propio día de liquidación: el caso "aprobado aquí y no liquidado" toma
 * todos los intentos APPROVED del día, así que los días no se comparten entre pruebas.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "payments.scheduling.enabled=false",
        "payments.outbox.relay-enabled=false",
        "payments.webhook.secrets=test-secret",
        "payments.reconciliation.first-check-seconds=0",
        "payments.provider.create-read-timeout=500ms",
        "payments.provider.query-read-timeout=500ms"
})
class SettlementIT {

    static final String PROVIDER = "DEMO";

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withStartupTimeout(java.time.Duration.ofMinutes(5));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ORACLE::getJdbcUrl);
        registry.add("spring.datasource.username", ORACLE::getUsername);
        registry.add("spring.datasource.password", ORACLE::getPassword);
    }

    @Autowired SettlementReconciliationService settlement;
    @Autowired PaymentAttemptRepository attempts;
    @Autowired OrderRepository orders;
    @Autowired OutboxRepository outbox;
    @Autowired JdbcClient jdbc;

    record DiscrepancyRow(String type, String attemptId, String providerPaymentId, String ourStatus,
                          String providerStatus, Long ourAmount, Long providerAmount, String ourCurrency,
                          String providerCurrency, String resolution, boolean resolved) {
    }

    // ---------------------------------------------------------------- (a) aprobado allá, no aquí

    @Test
    void approvedInSettlementButNotHere_isRecordedAndAutoFixedToApproved() {
        LocalDate day = LocalDate.of(2026, 2, 10); // sin solapamiento: el test del 11-ene crea datos del 10-ene
        PaymentAttempt pending = attempt(PaymentStatus.PENDING, "pay-" + UuidV7.next(), 150_000, "COP", day);
        String unknownPpid = "pay-" + UuidV7.next();
        List<SettlementLine> lines = List.of(
                new SettlementLine(pending.providerPaymentId(), pending.id(), "APPROVED", 150_000, "COP"),
                new SettlementLine(unknownPpid, null, "APPROVED", 5_000, "cop")); // desconocido aquí

        Summary summary = settlement.loadAndCompare(day, PROVIDER, lines);

        assertThat(summary.linesLoaded()).isEqualTo(2);
        assertThat(summary.autoFixed()).isEqualTo(1);
        assertThat(summary.openDiscrepancies()).isEqualTo(1); // solo el desconocido

        // Corrección con la transición estándar: intento APPROVED, orden PAID, historial y outbox.
        assertThat(attempts.findById(pending.id()).orElseThrow().status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(orders.findById(pending.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
        assertThat(outbox.countByAggregateAndType(pending.orderId(), EventPublisher.ORDER_PAID)).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM payment_status_history
                 WHERE attempt_id = :id AND source = 'SETTLEMENT' AND from_status = 'PENDING' AND to_status = 'APPROVED'
                """).param("id", pending.id()).query(Integer.class).single()).isEqualTo(1);

        List<DiscrepancyRow> rows = discrepancies(day);
        assertThat(rows).hasSize(2).allMatch(r -> r.type().equals("APPROVED_NOT_HERE"));
        DiscrepancyRow fixed = only(rows, pending.providerPaymentId());
        assertThat(fixed.attemptId()).isEqualTo(pending.id());
        assertThat(fixed.ourStatus()).isEqualTo("PENDING");
        assertThat(fixed.providerStatus()).isEqualTo("APPROVED");
        assertThat(fixed.resolution()).isEqualTo("AUTO_FIXED");
        assertThat(fixed.resolved()).isTrue();
        DiscrepancyRow unknown = only(rows, unknownPpid);
        assertThat(unknown.attemptId()).isNull();
        assertThat(unknown.ourStatus()).isNull();
        assertThat(unknown.providerCurrency()).isEqualTo("COP");
        assertThat(unknown.resolution()).isEqualTo("OPEN");

        // Segunda corrida del mismo día: el intento ya está APPROVED y el desconocido sigue abierto.
        Summary again = settlement.loadAndCompare(day, PROVIDER, lines);
        assertThat(again.autoFixed()).isZero();
        assertThat(discrepancies(day)).hasSize(2);
        assertThat(outbox.countByAggregateAndType(pending.orderId(), EventPublisher.ORDER_PAID)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- (b) aprobado aquí, no liquidado

    @Test
    void approvedHereButAbsentFromSettlement_isRecorded() {
        LocalDate day = LocalDate.of(2026, 1, 11);
        PaymentAttempt missing = attempt(PaymentStatus.APPROVED, "pay-" + UuidV7.next(), 150_000, "COP", day);
        PaymentAttempt settled = attempt(PaymentStatus.APPROVED, "pay-" + UuidV7.next(), 150_000, "COP", day);
        PaymentAttempt otherDay = attempt(PaymentStatus.APPROVED, "pay-" + UuidV7.next(), 150_000, "COP",
                day.minusDays(1));
        PaymentAttempt pending = attempt(PaymentStatus.PENDING, "pay-" + UuidV7.next(), 150_000, "COP", day);

        Summary summary = settlement.loadAndCompare(day, PROVIDER, List.of(
                new SettlementLine(settled.providerPaymentId(), settled.id(), "APPROVED", 150_000, "COP")));

        assertThat(summary.autoFixed()).isZero();
        assertThat(summary.openDiscrepancies()).isEqualTo(1);
        List<DiscrepancyRow> rows = discrepancies(day);
        assertThat(rows).hasSize(1);
        DiscrepancyRow row = rows.getFirst();
        assertThat(row.type()).isEqualTo("NOT_SETTLED");
        assertThat(row.attemptId()).isEqualTo(missing.id());
        assertThat(row.providerPaymentId()).isEqualTo(missing.providerPaymentId());
        assertThat(row.ourStatus()).isEqualTo("APPROVED");
        assertThat(row.providerStatus()).isNull();
        assertThat(row.ourAmount()).isEqualTo(150_000L);
        assertThat(row.providerAmount()).isNull();
        assertThat(row.resolution()).isEqualTo("OPEN");
        assertThat(rows).noneMatch(r -> otherDay.id().equals(r.attemptId()) || pending.id().equals(r.attemptId()));
    }

    // ---------------------------------------------------------------- (c) monto o moneda

    @Test
    void amountOrCurrencyMismatch_isRecordedAndLeftForManualReview() {
        LocalDate day = LocalDate.of(2026, 1, 12);
        PaymentAttempt wrongAmount = attempt(PaymentStatus.APPROVED, "pay-" + UuidV7.next(), 150_000, "COP", day);
        PaymentAttempt wrongCurrency = attempt(PaymentStatus.APPROVED, "pay-" + UuidV7.next(), 150_000, "COP", day);
        PaymentAttempt ok = attempt(PaymentStatus.APPROVED, "pay-" + UuidV7.next(), 150_000, "COP", day);

        settlement.loadAndCompare(day, PROVIDER, List.of(
                new SettlementLine(wrongAmount.providerPaymentId(), wrongAmount.id(), "APPROVED", 149_000, "COP"),
                // Sin nuestra referencia: se cruza por el id del proveedor.
                new SettlementLine(wrongCurrency.providerPaymentId(), null, "APPROVED", 150_000, "USD"),
                new SettlementLine(ok.providerPaymentId(), ok.id(), "APPROVED", 150_000, "COP")));

        List<DiscrepancyRow> rows = discrepancies(day);
        assertThat(rows).hasSize(2).allMatch(r -> r.type().equals("AMOUNT_MISMATCH") && r.resolution().equals("OPEN"));

        DiscrepancyRow amount = only(rows, wrongAmount.providerPaymentId());
        assertThat(amount.attemptId()).isEqualTo(wrongAmount.id());
        assertThat(amount.ourAmount()).isEqualTo(150_000L);
        assertThat(amount.providerAmount()).isEqualTo(149_000L);

        DiscrepancyRow currency = only(rows, wrongCurrency.providerPaymentId());
        assertThat(currency.attemptId()).isEqualTo(wrongCurrency.id());
        assertThat(currency.ourCurrency()).isEqualTo("COP");
        assertThat(currency.providerCurrency()).isEqualTo("USD");

        // Los montos no se corrigen solos.
        assertThat(attempts.findById(wrongAmount.id()).orElseThrow().amountMinor()).isEqualTo(150_000L);
    }

    // ---------------------------------------------------------------- idempotencia

    @Test
    void comparingTwiceTheSameDay_doesNotDuplicateOpenDiscrepancies() {
        LocalDate day = LocalDate.of(2026, 1, 13);
        // DECLINED aquí y APPROVED allá: contradicción de terminales, no se corrige sola.
        PaymentAttempt declined = attempt(PaymentStatus.DECLINED, "pay-" + UuidV7.next(), 150_000, "COP", day);
        PaymentAttempt notSettled = attempt(PaymentStatus.APPROVED, "pay-" + UuidV7.next(), 150_000, "COP", day);
        PaymentAttempt mismatch = attempt(PaymentStatus.APPROVED, "pay-" + UuidV7.next(), 150_000, "COP", day);
        List<SettlementLine> lines = List.of(
                new SettlementLine(declined.providerPaymentId(), declined.id(), "APPROVED", 150_000, "COP"),
                new SettlementLine(mismatch.providerPaymentId(), mismatch.id(), "APPROVED", 1_000, "COP"));

        Summary first = settlement.loadAndCompare(day, PROVIDER, lines);
        Summary second = settlement.loadAndCompare(day, PROVIDER, lines);
        settlement.compare(day, PROVIDER); // el procedimiento solo, otra vez, sobre el staging ya cargado

        assertThat(first.openDiscrepancies()).isEqualTo(3);
        assertThat(second.openDiscrepancies()).isEqualTo(3);
        assertThat(first.autoFixed()).isZero();
        List<DiscrepancyRow> rows = discrepancies(day);
        assertThat(rows).extracting(DiscrepancyRow::type)
                .containsExactlyInAnyOrder("APPROVED_NOT_HERE", "NOT_SETTLED", "AMOUNT_MISMATCH");
        assertThat(only(rows, declined.providerPaymentId()).type()).isEqualTo("APPROVED_NOT_HERE");
        assertThat(only(rows, notSettled.providerPaymentId()).type()).isEqualTo("NOT_SETTLED");
        assertThat(only(rows, mismatch.providerPaymentId()).type()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(attempts.findById(declined.id()).orElseThrow().status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM settlement_line WHERE run_date = :d AND provider = :p")
                .param("d", Date.valueOf(day)).param("p", PROVIDER).query(Integer.class).single()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- utilidades

    /** Orden + intento con el estado, id del proveedor y fecha de actualización indicados. */
    private PaymentAttempt attempt(PaymentStatus status, String providerPaymentId, long amount, String currency,
                                   LocalDate updatedOn) {
        String orderId = UuidV7.next();
        orders.insert(orderId, "customer-settlement", amount, currency, 30);
        String attemptId = UuidV7.next();
        attempts.insertCreated(attemptId, orderId, PROVIDER, amount, currency);
        jdbc.sql("UPDATE payment_attempt SET status = :status, provider_payment_id = :ppid, updated_at = :ts WHERE id = :id")
                .param("status", status.name()).param("ppid", providerPaymentId)
                .param("ts", Timestamp.valueOf(updatedOn.atTime(12, 0)))
                .param("id", attemptId)
                .update();
        return attempts.findById(attemptId).orElseThrow();
    }

    private List<DiscrepancyRow> discrepancies(LocalDate day) {
        return jdbc.sql("""
                SELECT discrepancy_type, attempt_id, provider_payment_id, our_status, provider_status,
                       our_amount, provider_amount, our_currency, provider_currency, resolution, resolved_at
                  FROM reconciliation_discrepancy
                 WHERE run_type = 'SETTLEMENT' AND settlement_date = :day AND provider = :provider
                 ORDER BY id
                """)
                .param("day", Date.valueOf(day)).param("provider", PROVIDER)
                .query((rs, n) -> new DiscrepancyRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getObject(6, Long.class), rs.getObject(7, Long.class),
                        trim(rs.getString(8)), trim(rs.getString(9)), rs.getString(10), rs.getTimestamp(11) != null))
                .list();
    }

    private static DiscrepancyRow only(List<DiscrepancyRow> rows, String providerPaymentId) {
        List<DiscrepancyRow> matching = rows.stream().filter(r -> providerPaymentId.equals(r.providerPaymentId())).toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
