package com.pagos.payments.application;

import com.pagos.payments.domain.PaymentAttempt;
import com.pagos.payments.domain.PaymentStatus;
import com.pagos.payments.domain.ProviderEvidence;
import com.pagos.payments.domain.TransitionResult;
import com.pagos.payments.domain.TransitionSource;
import com.pagos.payments.persistence.PaymentAttemptRepository;
import com.pagos.payments.persistence.ProviderEventRepository;
import com.pagos.payments.persistence.ProviderEventRepository.EventRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica un evento del inbox. La transición y la marca del evento van en la misma transacción:
 * o el evento queda PROCESSED con su efecto, o no queda nada y se reintenta.
 */
@Component
public class InboxEventHandler {

    private final ProviderEventRepository events;
    private final PaymentAttemptRepository attempts;
    private final PaymentTransitionService transitions;

    public InboxEventHandler(ProviderEventRepository events, PaymentAttemptRepository attempts,
                             PaymentTransitionService transitions) {
        this.events = events;
        this.attempts = attempts;
        this.transitions = transitions;
    }

    @Transactional
    public void handle(long eventId) {
        // FOR UPDATE: si el lease venció y otra instancia toma el mismo evento, espera aquí
        // y luego ve que ya no está RECEIVED. Así nunca se sobrescribe el resultado.
        EventRow event = events.lockById(eventId).orElseThrow();
        if (!"RECEIVED".equals(event.processingStatus())) {
            return; // ya procesado por otra instancia
        }
        if (event.reportedStatus() == null) {
            throw new IllegalStateException("Estado del proveedor no reconocido");
        }
        PaymentAttempt attempt = findAttempt(event);
        PaymentStatus target = PaymentStatus.valueOf(event.reportedStatus());

        TransitionResult result = transitions.transition(attempt.id(), target, TransitionSource.WEBHOOK, event.id(),
                new ProviderEvidence(event.providerPaymentId(), event.reportedAmount(), event.reportedCurrency()),
                null);

        events.markDone(eventId, result.applied() ? "PROCESSED" : "IGNORED_STALE");
    }

    private PaymentAttempt findAttempt(EventRow event) {
        // La referencia es nuestro id de intento, confirmado antes de llamar al proveedor:
        // existe aunque el webhook llegue antes que la respuesta de creación.
        if (event.merchantReference() != null) {
            var byReference = attempts.findById(event.merchantReference());
            if (byReference.isPresent()) {
                return byReference.get();
            }
        }
        if (event.providerPaymentId() != null) {
            var byProviderId = attempts.findByProviderPaymentId(event.provider(), event.providerPaymentId());
            if (byProviderId.isPresent()) {
                return byProviderId.get();
            }
        }
        throw new IllegalStateException("Referencia desconocida: " + event.merchantReference());
    }
}
