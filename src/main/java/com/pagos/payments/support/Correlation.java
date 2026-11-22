package com.pagos.payments.support;

import org.slf4j.MDC;

/** Acceso al correlation id del hilo actual (lo fija {@code CorrelationFilter} o el job). */
public final class Correlation {

    public static final String MDC_KEY = "correlation_id";
    public static final String HEADER = "X-Correlation-Id";

    private Correlation() {
    }

    public static String id() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : UuidV7.next();
    }

    /** Ejecuta un bloque con un correlation id propio (jobs y procesadores asíncronos). */
    public static void run(String correlationId, Runnable action) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, correlationId);
        try {
            action.run();
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
