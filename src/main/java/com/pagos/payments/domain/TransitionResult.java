package com.pagos.payments.domain;

/**
 * Resultado de intentar una transición.
 *
 * @param applied       true si el UPDATE condicional afectó 1 fila y se aplicaron los efectos
 * @param currentStatus estado del intento después de la operación
 */
public record TransitionResult(boolean applied, PaymentStatus currentStatus) {

    public static TransitionResult applied(PaymentStatus status) {
        return new TransitionResult(true, status);
    }

    /** Duplicado (ya estaba en el destino) u obsoleto (ya había avanzado): no-op. */
    public static TransitionResult ignored(PaymentStatus current) {
        return new TransitionResult(false, current);
    }
}
