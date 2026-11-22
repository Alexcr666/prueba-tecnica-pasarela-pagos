package com.pagos.payments.application;

import static com.pagos.payments.domain.OrderStatus.AWAITING_PAYMENT;
import static com.pagos.payments.domain.OrderStatus.CANCELLED;
import static com.pagos.payments.domain.OrderStatus.EXPIRED;
import static com.pagos.payments.domain.OrderStatus.PAID;
import static com.pagos.payments.domain.OrderStatus.REQUIRES_REVIEW;

import com.pagos.payments.domain.PaymentAttempt;
import com.pagos.payments.domain.PaymentStateMachine;
import com.pagos.payments.domain.PaymentStatus;
import com.pagos.payments.domain.ProviderEvidence;
import com.pagos.payments.domain.TransitionResult;
import com.pagos.payments.domain.TransitionSource;
import com.pagos.payments.persistence.DiscrepancyRepository;
import com.pagos.payments.persistence.HistoryRepository;
import com.pagos.payments.persistence.OrderRepository;
import com.pagos.payments.persistence.OrderRepository.OrderRow;
import com.pagos.payments.persistence.PaymentAttemptRepository;
import com.pagos.payments.support.Correlation;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Único punto por el que cambia el estado de un intento de pago (sección 8 del diseño).
 *
 * <p>Lo usan la API, el procesador del inbox (webhooks) y la conciliación. Garantías:
 * <ol>
 *   <li>Bloquea primero la orden y luego actúa sobre el intento: sin interbloqueos y con los
 *       eventos de una misma orden serializados.</li>
 *   <li>El UPDATE condicional decide: 1 fila = se aplica; 0 filas = duplicado u obsoleto.</li>
 *   <li>Historial, efecto sobre la orden y evento del outbox van en la misma transacción.</li>
 * </ol>
 */
@Service
public class PaymentTransitionService {

    private static final Logger log = LoggerFactory.getLogger(PaymentTransitionService.class);

    private final PaymentAttemptRepository attempts;
    private final OrderRepository orders;
    private final HistoryRepository history;
    private final DiscrepancyRepository discrepancies;
    private final EventPublisher events;
    private final MeterRegistry metrics;

    public PaymentTransitionService(PaymentAttemptRepository attempts, OrderRepository orders,
                                    HistoryRepository history, DiscrepancyRepository discrepancies,
                                    EventPublisher events, MeterRegistry metrics) {
        this.attempts = attempts;
        this.orders = orders;
        this.history = history;
        this.discrepancies = discrepancies;
        this.events = events;
        this.metrics = metrics;
    }

    @Transactional
    public TransitionResult transition(String attemptId, PaymentStatus target, TransitionSource source,
                                       Long providerEventRef, ProviderEvidence evidence, String reason) {
        PaymentAttempt snapshot = attempts.findById(attemptId)
                .orElseThrow(() -> new IllegalArgumentException("Intento desconocido: " + attemptId));

        // 1) Siempre primero la orden. Tras obtener el bloqueo se relee el intento: otra
        //    transacción pudo cambiarlo mientras esperábamos.
        OrderRow order = orders.lockForUpdate(snapshot.orderId()).orElseThrow();
        PaymentAttempt before = attempts.findById(attemptId).orElseThrow();

        if (evidence.providerPaymentId() != null) {
            attempts.attachProviderData(attemptId, evidence.providerPaymentId(), null);
        }

        // 2) El UPDATE condicional es la fuente de verdad de la idempotencia.
        int rows = attempts.transitionIf(attemptId, target, PaymentStateMachine.allowedSourceNamesOf(target), reason);
        if (rows == 0) {
            PaymentStatus current = attempts.findById(attemptId).orElseThrow().status();
            detectContradiction(before, current, target, source);
            count(before.status(), target, source, "ignored");
            log.info("Transición ignorada attempt_id={} actual={} destino={} source={}",
                    attemptId, current, target, source);
            return TransitionResult.ignored(current);
        }

        // 3) Auditoría + evento, en el mismo commit que el cambio.
        history.append(attemptId, before.orderId(), before.status(), target, source, providerEventRef,
                Correlation.id(), reason);
        events.paymentStatusChanged(before, before.status(), target);

        // 4) Efecto sobre la orden.
        if (target == PaymentStatus.APPROVED) {
            applyApprovalToOrder(before, order, evidence);
        }

        count(before.status(), target, source, "applied");
        log.info("Transición aplicada attempt_id={} order_id={} from={} to={} source={}",
                attemptId, before.orderId(), before.status(), target, source);
        return TransitionResult.applied(target);
    }

    /** Reglas R2 y R4 del diseño. Se ejecuta con la orden bloqueada: su estado es el vigente. */
    private void applyApprovalToOrder(PaymentAttempt attempt, OrderRow order, ProviderEvidence evidence) {
        if (!evidence.amountMatches(attempt)) {
            orders.updateStatusIf(order.id(), List.of(AWAITING_PAYMENT, EXPIRED, CANCELLED), REQUIRES_REVIEW);
            discrepancies.record("INCREMENTAL", attempt.id(), attempt.providerPaymentId(), "APPROVED", "APPROVED",
                    attempt.amountMinor(), evidence.amountMinor());
            events.needsReview(attempt, "AMOUNT_MISMATCH");
            if (order.status() == PAID) {
                // Cobro adicional sobre una orden ya pagada por otro intento: se reembolsa (R2).
                events.refundRequired(attempt, "ORDER_ALREADY_PAID");
            }
            log.error("Monto aprobado distinto al esperado attempt_id={} esperado={} recibido={}",
                    attempt.id(), attempt.amountMinor(), evidence.amountMinor());
            return;
        }
        switch (order.status()) {
            case AWAITING_PAYMENT -> {
                orders.updateStatusIf(order.id(), List.of(AWAITING_PAYMENT), PAID);
                events.orderPaid(attempt);
            }
            case PAID -> {
                // Cobro real sobre una orden ya pagada por otro intento: se reembolsa (R2).
                events.refundRequired(attempt, "ORDER_ALREADY_PAID");
                log.error("Segundo cobro aprobado para orden pagada order_id={} attempt_id={}",
                        order.id(), attempt.id());
            }
            case EXPIRED, CANCELLED -> {
                orders.updateStatusIf(order.id(), List.of(EXPIRED, CANCELLED), REQUIRES_REVIEW);
                events.refundRequired(attempt, "ORDER_" + order.status());
                log.error("Pago aprobado sobre orden {} order_id={} attempt_id={}",
                        order.status(), order.id(), attempt.id());
            }
            case REQUIRES_REVIEW -> events.needsReview(attempt, "ORDER_UNDER_REVIEW");
        }
    }

    /** Dos terminales del proveedor distintos (p. ej. APPROVED y luego DECLINED): alerta crítica. */
    private void detectContradiction(PaymentAttempt before, PaymentStatus current, PaymentStatus target,
                                     TransitionSource source) {
        if (current.isProviderTerminal() && target.isProviderTerminal() && current != target) {
            discrepancies.record("INCREMENTAL", before.id(), before.providerPaymentId(), current.name(),
                    target.name(), before.amountMinor(), null);
            metrics.counter("payments.contradictions", "source", source.name()).increment();
            log.error("Evidencia contradictoria attempt_id={} actual={} recibido={} source={}",
                    before.id(), current, target, source);
        }
    }

    private void count(PaymentStatus from, PaymentStatus to, TransitionSource source, String result) {
        metrics.counter("payments.transitions", "from", from.name(), "to", to.name(),
                "source", source.name(), "result", result).increment();
    }
}
