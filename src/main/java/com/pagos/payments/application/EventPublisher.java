package com.pagos.payments.application;

import com.pagos.payments.domain.PaymentAttempt;
import com.pagos.payments.domain.PaymentStatus;
import com.pagos.payments.persistence.OutboxRepository;
import com.pagos.payments.support.Correlation;
import com.pagos.payments.support.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Escribe eventos de dominio en el outbox. Exige una transacción activa: el evento se confirma
 * junto con el cambio de estado o no se confirma.
 *
 * <p>Contrato del mensaje: un sobre con metadatos (inspirado en CloudEvents) y los datos del
 * negocio agrupados por concepto.
 *
 * <pre>
 * {
 *   "eventId": "...",            // = message_id AMQP = id en outbox_event (deduplicación)
 *   "eventType": "PaymentStatusChanged",
 *   "schemaVersion": 1,          // cambios incompatibles => nueva versión
 *   "occurredAt": "2026-...Z",
 *   "source": "payment-service",
 *   "correlationId": "...",
 *   "data": {
 *     "payment":    { "attemptId", "orderId", "provider", "providerPaymentId" },
 *     "amount":     { "valueMinor": 150000, "currency": "COP" },
 *     "transition": { "from": "PENDING", "to": "APPROVED" }   // solo PaymentStatusChanged
 *     "reason": "..."                                          // solo RefundRequired / PaymentNeedsReview
 *   }
 * }
 * </pre>
 */
@Component
public class EventPublisher {

    public static final String PAYMENT_STATUS_CHANGED = "PaymentStatusChanged";
    public static final String ORDER_PAID = "OrderPaid";
    public static final String REFUND_REQUIRED = "RefundRequired";
    public static final String PAYMENT_NEEDS_REVIEW = "PaymentNeedsReview";

    static final int SCHEMA_VERSION = 1;
    static final String SOURCE = "payment-service";

    private final OutboxRepository outbox;
    private final JsonMapper json;
    private final Clock clock;

    public EventPublisher(OutboxRepository outbox, JsonMapper json, Clock clock) {
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentStatusChanged(PaymentAttempt attempt, PaymentStatus from, PaymentStatus to) {
        Map<String, Object> data = data(attempt);
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("from", from == null ? null : from.name());
        transition.put("to", to.name());
        data.put("transition", transition);
        publish("PAYMENT", attempt.id(), PAYMENT_STATUS_CHANGED, data);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void orderPaid(PaymentAttempt attempt) {
        publish("ORDER", attempt.orderId(), ORDER_PAID, data(attempt));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void refundRequired(PaymentAttempt attempt, String reason) {
        Map<String, Object> data = data(attempt);
        data.put("reason", reason);
        publish("PAYMENT", attempt.id(), REFUND_REQUIRED, data);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void needsReview(PaymentAttempt attempt, String reason) {
        Map<String, Object> data = data(attempt);
        data.put("reason", reason);
        publish("PAYMENT", attempt.id(), PAYMENT_NEEDS_REVIEW, data);
    }

    private void publish(String aggregateType, String aggregateId, String eventType, Map<String, Object> data) {
        String eventId = UuidV7.next();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("occurredAt", Instant.now(clock).toString());
        envelope.put("source", SOURCE);
        envelope.put("correlationId", Correlation.id());
        envelope.put("data", data);
        outbox.add(eventId, aggregateType, aggregateId, eventType, json.writeValueAsString(envelope));
    }

    private static Map<String, Object> data(PaymentAttempt attempt) {
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("attemptId", attempt.id());
        payment.put("orderId", attempt.orderId());
        payment.put("provider", attempt.provider());
        payment.put("providerPaymentId", attempt.providerPaymentId());

        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("valueMinor", attempt.amountMinor());
        amount.put("currency", attempt.currency());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("payment", payment);
        data.put("amount", amount);
        return data;
    }
}
