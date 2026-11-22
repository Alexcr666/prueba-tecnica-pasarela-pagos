package com.pagos.payments.domain;

import java.util.EnumSet;
import java.util.Set;

/** Estados de un intento de pago. Ver sección 5 del diseño. */
public enum PaymentStatus {
    CREATED,
    PENDING,
    UNKNOWN,
    APPROVED,
    DECLINED,
    EXPIRED,
    FAILED;

    private static final Set<PaymentStatus> ACTIVE = EnumSet.of(CREATED, PENDING, UNKNOWN);
    private static final Set<PaymentStatus> PROVIDER_TERMINAL = EnumSet.of(APPROVED, DECLINED, EXPIRED);

    /** Un intento activo bloquea la creación de otro para la misma orden (regla R1). */
    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    /** Terminal informado por el proveedor: definitivo, ninguna evidencia lo cambia. */
    public boolean isProviderTerminal() {
        return PROVIDER_TERMINAL.contains(this);
    }
}
