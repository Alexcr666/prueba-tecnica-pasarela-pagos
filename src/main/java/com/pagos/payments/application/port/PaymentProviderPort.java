package com.pagos.payments.application.port;

import com.pagos.payments.domain.PaymentStatus;

/**
 * Puerto hacia la pasarela de pagos (arquitectura hexagonal).
 *
 * <p>Los resultados distinguen explícitamente lo que se SABE de lo que NO se sabe: un timeout
 * de lectura no es un rechazo, es {@link CreateResult.Uncertain}.
 */
public interface PaymentProviderPort {

    String name();

    /** false si el circuit breaker está abierto: no se debe iniciar un pago nuevo. */
    boolean isAvailable();

    CreateResult create(CreatePaymentRequest request);

    LookupResult findByReference(String merchantReference);

    record CreatePaymentRequest(String merchantReference, long amountMinor, String currency,
                                String returnUrl, String notificationUrl) {
    }

    sealed interface CreateResult {
        /** El proveedor aceptó y devolvió su id y la URL de redirección. */
        record Accepted(String providerPaymentId, String redirectUrl, PaymentStatus status) implements CreateResult {
        }

        /** Rechazo síncrono informado por el proveedor (terminal del proveedor). */
        record Declined(String providerPaymentId, String reason) implements CreateResult {
        }

        /** Con certeza no se creó nada: fallo de conexión, 4xx o circuito abierto. */
        record NotCreated(String reason) implements CreateResult {
        }

        /** No se sabe si se creó: timeout de lectura o 5xx. Nunca se reintenta a ciegas. */
        record Uncertain(String reason) implements CreateResult {
        }
    }

    sealed interface LookupResult {
        record Found(String providerPaymentId, PaymentStatus status, Long amountMinor, String currency)
                implements LookupResult {
        }

        record NotFound() implements LookupResult {
        }

        /** No se pudo consultar (proveedor caído o lento): se reprograma. */
        record Unavailable(String reason) implements LookupResult {
        }
    }
}
