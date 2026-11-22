package com.pagos.payments.domain;

import static com.pagos.payments.domain.PaymentStatus.*;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Especificación de transiciones del intento de pago (tabla 5.1 del diseño).
 *
 * <p>Cada estado destino declara desde qué estados se puede llegar a él. Esa lista es
 * exactamente el {@code status IN (...)} del UPDATE condicional: la base de datos, no la
 * memoria, decide si la transición se aplica. Un evento duplicado u obsoleto actualiza 0 filas.
 */
public final class PaymentStateMachine {

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_SOURCES = new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED_SOURCES.put(CREATED, EnumSet.noneOf(PaymentStatus.class)); // solo por INSERT
        ALLOWED_SOURCES.put(PENDING, EnumSet.of(CREATED, UNKNOWN));
        ALLOWED_SOURCES.put(UNKNOWN, EnumSet.of(CREATED));
        // FAILED es un terminal inferido por nosotros: la evidencia del proveedor lo corrige.
        ALLOWED_SOURCES.put(APPROVED, EnumSet.of(CREATED, PENDING, UNKNOWN, FAILED));
        ALLOWED_SOURCES.put(DECLINED, EnumSet.of(CREATED, PENDING, UNKNOWN, FAILED));
        // CREATED incluido: un intento huérfano puede encontrarse ya expirado en el proveedor.
        ALLOWED_SOURCES.put(EXPIRED, EnumSet.of(CREATED, PENDING, UNKNOWN, FAILED));
        ALLOWED_SOURCES.put(FAILED, EnumSet.of(CREATED, UNKNOWN));
    }

    private PaymentStateMachine() {
    }

    public static Set<PaymentStatus> allowedSourcesOf(PaymentStatus target) {
        return Collections.unmodifiableSet(ALLOWED_SOURCES.get(target));
    }

    /** Nombres de los orígenes permitidos, listos para el {@code IN (:allowedFrom)} del SQL. */
    public static List<String> allowedSourceNamesOf(PaymentStatus target) {
        return ALLOWED_SOURCES.get(target).stream().map(Enum::name).toList();
    }

    public static boolean canTransition(PaymentStatus from, PaymentStatus to) {
        return ALLOWED_SOURCES.get(to).contains(from);
    }
}
