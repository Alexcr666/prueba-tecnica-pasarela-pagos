package com.pagos.payments.application;

import com.pagos.payments.application.port.PaymentProviderPort;
import com.pagos.payments.application.port.PaymentProviderPort.LookupResult;
import com.pagos.payments.config.PaymentsProperties;
import com.pagos.payments.domain.PaymentAttempt;
import com.pagos.payments.domain.PaymentStatus;
import com.pagos.payments.domain.ProviderEvidence;
import com.pagos.payments.domain.TransitionSource;
import com.pagos.payments.persistence.Db;
import com.pagos.payments.persistence.PaymentAttemptRepository;
import com.pagos.payments.support.Correlation;
import com.pagos.payments.support.UuidV7;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Conciliación incremental (sección 9.2): resuelve intentos CREATED huérfanos, UNKNOWN y PENDING
 * sin avance consultando al proveedor por la referencia. Nunca resuelve sin evidencia.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PaymentAttemptRepository attempts;
    private final PaymentProviderPort provider;
    private final PaymentTransitionService transitions;
    private final TransactionTemplate tx;
    private final PaymentsProperties.Reconciliation config;
    private final MeterRegistry metrics;
    private final Db db;

    public ReconciliationService(PaymentAttemptRepository attempts, PaymentProviderPort provider,
                                 PaymentTransitionService transitions, TransactionTemplate tx,
                                 PaymentsProperties props, MeterRegistry metrics, Db db) {
        this.attempts = attempts;
        this.provider = provider;
        this.transitions = transitions;
        this.tx = tx;
        this.config = props.reconciliation();
        this.metrics = metrics;
        this.db = db;
    }

    @Scheduled(fixedDelayString = "${payments.reconciliation.interval-ms:60000}")
    @SchedulerLock(name = "payments-reconciliation", lockAtMostFor = "PT5M")
    public void scheduledRun() {
        if (config.enabled() && provider.isAvailable()) {
            runOnce();
        }
    }

    public int runOnce() {
        // Transacción corta: reclamar + lease. La llamada HTTP va fuera de toda transacción.
        List<String> ids = tx.execute(status -> attempts.claimForReconciliation(
                config.batchSize(), config.createdOrphanSeconds(), config.pendingStaleSeconds()));
        for (String id : ids) {
            Correlation.run(UuidV7.next(), () -> reconcile(id));
        }
        return ids.size();
    }

    void reconcile(String attemptId) {
        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow();
        if (!attempt.status().isActive()) {
            return; // ya resuelto por un webhook mientras tanto
        }
        LookupResult lookup = provider.findByReference(attemptId);
        switch (lookup) {
            case LookupResult.Found found -> {
                if (found.status() != attempt.status()) {
                    transitions.transition(attemptId, found.status(), TransitionSource.RECONCILIATION, null,
                            new ProviderEvidence(found.providerPaymentId(), found.amountMinor(), found.currency()),
                            "reconciliation");
                }
            }
            case LookupResult.NotFound notFound -> handleNotFound(attempt);
            case LookupResult.Unavailable unavailable -> {
                metrics.counter("payments.reconciliation.unavailable").increment();
                log.info("Conciliación aplazada attempt_id={} motivo={}", attemptId, unavailable.reason());
            }
        }
        checkExhausted(attempt);
    }

    private void handleNotFound(PaymentAttempt attempt) {
        Duration age = Duration.between(attempt.createdAt(), db.now()); // created_at lo fija Oracle
        if (age.toMinutes() >= config.notFoundFailMinutes()) {
            // FAILED es un terminal inferido: si el proveedor luego informa un cobro, se corrige (R2).
            transitions.transition(attempt.id(), PaymentStatus.FAILED, TransitionSource.RECONCILIATION, null,
                    ProviderEvidence.NONE, "not found at provider");
        } else if (attempt.status() == PaymentStatus.CREATED) {
            transitions.transition(attempt.id(), PaymentStatus.UNKNOWN, TransitionSource.RECONCILIATION, null,
                    ProviderEvidence.NONE, "orphan CREATED attempt");
        }
    }

    /** Regla R5: tras 48 h sin resolver, se alerta para revisión manual. */
    private void checkExhausted(PaymentAttempt attempt) {
        Duration age = Duration.between(attempt.createdAt(), db.now());
        if (attempt.status() == PaymentStatus.UNKNOWN && age.toHours() >= config.maxUncertainHours()) {
            metrics.counter("payments.reconciliation.exhausted").increment();
            log.error("Intento UNKNOWN sin resolver tras {} h attempt_id={}: requiere revisión manual",
                    age.toHours(), attempt.id());
        }
    }
}
