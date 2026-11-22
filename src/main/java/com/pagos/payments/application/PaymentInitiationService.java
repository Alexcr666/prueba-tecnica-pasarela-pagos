package com.pagos.payments.application;

import com.pagos.payments.application.port.PaymentProviderPort;
import com.pagos.payments.application.port.PaymentProviderPort.CreatePaymentRequest;
import com.pagos.payments.application.port.PaymentProviderPort.CreateResult;
import com.pagos.payments.config.PaymentsProperties;
import com.pagos.payments.domain.OrderStatus;
import com.pagos.payments.domain.PaymentAttempt;
import com.pagos.payments.domain.PaymentStatus;
import com.pagos.payments.domain.ProviderEvidence;
import com.pagos.payments.domain.TransitionSource;
import com.pagos.payments.persistence.HistoryRepository;
import com.pagos.payments.persistence.IdempotencyRepository;
import com.pagos.payments.persistence.OrderRepository;
import com.pagos.payments.persistence.OrderRepository.OrderRow;
import com.pagos.payments.persistence.PaymentAttemptRepository;
import com.pagos.payments.support.Correlation;
import com.pagos.payments.support.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inicia un pago con dos transacciones cortas alrededor de la llamada al proveedor
 * (decisión 4.2.1 del diseño):
 * <ol>
 *   <li>TX1: idempotencia + bloqueo de la orden + INSERT del intento en CREATED → commit.</li>
 *   <li>Llamada HTTP sin transacción ni bloqueos abiertos.</li>
 *   <li>TX2: transición según el resultado (PENDING, DECLINED, FAILED o UNKNOWN).</li>
 * </ol>
 * Si el proceso muere entre 1 y 3, el intento queda en CREATED y la conciliación lo resuelve.
 */
@Service
public class PaymentInitiationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentInitiationService.class);

    public record InitiationResult(PaymentAttempt attempt, boolean replay) {
    }

    private sealed interface Prepared {
        record Fresh(PaymentAttempt attempt) implements Prepared {
        }

        record Replay(String attemptId) implements Prepared {
        }
    }

    private final PaymentProviderPort provider;
    private final PaymentAttemptRepository attempts;
    private final OrderRepository orders;
    private final IdempotencyRepository idempotency;
    private final HistoryRepository history;
    private final PaymentTransitionService transitions;
    private final TransactionTemplate tx;
    private final PaymentsProperties props;

    public PaymentInitiationService(PaymentProviderPort provider, PaymentAttemptRepository attempts,
                                    OrderRepository orders, IdempotencyRepository idempotency,
                                    HistoryRepository history, PaymentTransitionService transitions,
                                    TransactionTemplate tx, PaymentsProperties props) {
        this.provider = provider;
        this.attempts = attempts;
        this.orders = orders;
        this.idempotency = idempotency;
        this.history = history;
        this.transitions = transitions;
        this.tx = tx;
        this.props = props;
    }

    public InitiationResult initiate(String orderId, String idempotencyKey) {
        if (!provider.isAvailable()) {
            // Circuito abierto: se responde 503 sin crear el intento.
            throw ApiException.providerUnavailable();
        }

        Prepared prepared = tx.execute(status -> prepare(orderId, idempotencyKey));
        if (prepared instanceof Prepared.Replay replay) {
            return new InitiationResult(attempts.findById(replay.attemptId()).orElseThrow(), true);
        }
        PaymentAttempt attempt = ((Prepared.Fresh) prepared).attempt();

        CreateResult result = provider.create(new CreatePaymentRequest(attempt.id(), attempt.amountMinor(),
                attempt.currency(), props.provider().returnUrl(), props.provider().notificationUrl()));

        applyCreateResult(attempt, result);
        return new InitiationResult(attempts.findById(attempt.id()).orElseThrow(), false);
    }

    private Prepared prepare(String orderId, String idempotencyKey) {
        String scope = "POST /orders/" + orderId + "/payments";
        String attemptId = UuidV7.next();
        if (!idempotency.tryInsert(scope, idempotencyKey, orderId, attemptId)) {
            return new Prepared.Replay(idempotency.find(scope, idempotencyKey).orElseThrow().resourceId());
        }

        // Regla de concurrencia: primero la orden.
        OrderRow order = orders.lockForUpdate(orderId).orElseThrow(() -> ApiException.notFound("La orden"));
        if (order.status() != OrderStatus.AWAITING_PAYMENT || order.expired()) {
            throw ApiException.orderNotPayable(order.expired() ? "EXPIRED" : order.status().name());
        }

        try {
            attempts.insertCreated(attemptId, orderId, provider.name(), order.amountMinor(), order.currency());
        } catch (DuplicateKeyException activeExists) {
            // UX_ATTEMPT_ACTIVE: ya hay un intento CREATED/PENDING/UNKNOWN (regla R1).
            // La excepción revierte la TX, incluida la clave de idempotencia.
            String active = attempts.findActiveByOrder(orderId).map(PaymentAttempt::id).orElse("?");
            throw ApiException.activePaymentExists(active);
        }
        history.append(attemptId, orderId, null, PaymentStatus.CREATED, TransitionSource.API, null,
                Correlation.id(), null);
        return new Prepared.Fresh(attempts.findById(attemptId).orElseThrow());
    }

    private void applyCreateResult(PaymentAttempt attempt, CreateResult result) {
        String id = attempt.id();
        switch (result) {
            case CreateResult.Accepted ok -> {
                // Se guardan los datos del proveedor aunque un webhook ya haya hecho avanzar el estado.
                attempts.attachProviderData(id, ok.providerPaymentId(), ok.redirectUrl());
                transitions.transition(id, ok.status(), TransitionSource.API, null,
                        new ProviderEvidence(ok.providerPaymentId(), null, null), null);
            }
            case CreateResult.Declined declined -> transitions.transition(id, PaymentStatus.DECLINED,
                    TransitionSource.API, null, new ProviderEvidence(declined.providerPaymentId(), null, null),
                    declined.reason());
            case CreateResult.NotCreated notCreated -> transitions.transition(id, PaymentStatus.FAILED,
                    TransitionSource.API, null, ProviderEvidence.NONE, notCreated.reason());
            case CreateResult.Uncertain uncertain -> {
                // No se sabe si existe el cobro: nunca se reintenta a ciegas.
                transitions.transition(id, PaymentStatus.UNKNOWN, TransitionSource.API, null,
                        ProviderEvidence.NONE, uncertain.reason());
                attempts.scheduleCheck(id, props.reconciliation().firstCheckSeconds());
                log.warn("Resultado incierto al crear el pago attempt_id={} motivo={}", id, uncertain.reason());
            }
        }
    }
}
