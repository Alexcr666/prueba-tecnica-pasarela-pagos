package com.pagos.payments.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuración tipada. Los valores por defecto están en application.yml (sección 9 del diseño). */
@ConfigurationProperties(prefix = "payments")
public record PaymentsProperties(
        int orderTtlMinutes,
        Provider provider,
        Webhook webhook,
        Inbox inbox,
        Outbox outbox,
        Reconciliation reconciliation,
        Expiration expiration) {

    public record Provider(
            String name,
            String baseUrl,
            String apiKey,
            Duration connectTimeout,
            Duration createReadTimeout,
            Duration queryReadTimeout,
            String returnUrl,
            String notificationUrl) {
    }

    /** Dos secretos vigentes permiten rotar sin cortar la recepción. */
    public record Webhook(List<String> secrets, Duration tolerance, int maxBodyBytes) {
    }

    public record Inbox(boolean enabled, int batchSize, int leaseSeconds, int maxAttempts) {
    }

    public record Outbox(boolean relayEnabled, int batchSize, int leaseSeconds, String exchange, long confirmTimeoutMs) {
    }

    public record Reconciliation(boolean enabled, int batchSize, int createdOrphanSeconds, int pendingStaleSeconds,
                                 int firstCheckSeconds, int notFoundFailMinutes, int maxUncertainHours) {
    }

    public record Expiration(boolean enabled) {
    }
}
