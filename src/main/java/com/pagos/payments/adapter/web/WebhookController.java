package com.pagos.payments.adapter.web;

import com.pagos.payments.adapter.provider.ProviderStatusMapper;
import com.pagos.payments.config.PaymentsProperties;
import com.pagos.payments.domain.PaymentStatus;
import com.pagos.payments.persistence.ProviderEventRepository;
import com.pagos.payments.persistence.ProviderEventRepository.NewEvent;
import com.pagos.payments.support.Hashing;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receptor de webhooks: verifica, guarda en el inbox y responde. No aplica efectos de negocio;
 * así responde en milisegundos y un fallo posterior no pierde el evento.
 *
 * <p>Contrato asumido (supuesto S4):
 * <pre>{"id":"evt_1","type":"payment.updated",
 *  "data":{"id":"pay_1","reference":"&lt;attemptId&gt;","status":"APPROVED","amount":150000,"currency":"COP"}}</pre>
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookSignatureVerifier verifier;
    private final ProviderEventRepository inbox;
    private final JsonMapper json;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final int maxBodyBytes;
    private final String providerName;

    public WebhookController(PaymentsProperties props, ProviderEventRepository inbox, JsonMapper json,
                             MeterRegistry metrics, Clock clock) {
        this.verifier = new WebhookSignatureVerifier(props.webhook().secrets(), props.webhook().tolerance());
        this.inbox = inbox;
        this.json = json;
        this.metrics = metrics;
        this.clock = clock;
        this.maxBodyBytes = props.webhook().maxBodyBytes();
        this.providerName = props.provider().name();
    }

    @PostMapping("/webhooks/{provider}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable String provider,
            @RequestHeader(value = "X-Provider-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Provider-Signature", required = false) String signature,
            @RequestBody String rawBody) {

        // Proveedor no configurado: 404 antes de tocar nada. Evita además que un valor
        // arbitrario de la URL llegue como etiqueta de métrica (cardinalidad ilimitada).
        if (!providerName.equals(provider)) {
            return ResponseEntity.notFound().build();
        }
        if (rawBody.getBytes(StandardCharsets.UTF_8).length > maxBodyBytes) {
            return ResponseEntity.status(413).build();
        }
        if (!verifier.verify(timestamp, signature, rawBody, clock.instant())) {
            // No se persiste nada que no esté autenticado.
            metrics.counter("payments.webhook.signature_failures", "provider", provider).increment();
            log.warn("Webhook con firma inválida provider={}", provider);
            return ResponseEntity.status(401).build();
        }

        NewEvent event;
        try {
            event = parse(provider, rawBody);
        } catch (JacksonException | IllegalArgumentException malformed) {
            return ResponseEntity.badRequest().build();
        }

        // Si Oracle no responde, la excepción produce un 503 y el proveedor reintenta (S5).
        boolean duplicate = inbox.insertIfAbsent(event).isEmpty();
        if (duplicate) {
            metrics.counter("payments.webhook.duplicates", "provider", provider).increment();
        }
        log.info("Webhook recibido provider={} provider_event_id={} reference={} duplicate={}",
                provider, event.providerEventId(), event.merchantReference(), duplicate);
        return ResponseEntity.ok(Map.of("received", true, "duplicate", duplicate));
    }

    private NewEvent parse(String provider, String rawBody) {
        JsonNode root = json.readTree(rawBody);
        JsonNode data = root.path("data");
        String providerStatus = text(data, "status");
        String eventId = eventKey(text(root, "id"), text(data, "id"), providerStatus);
        PaymentStatus mapped = ProviderStatusMapper.map(providerStatus);
        Long amount = data.hasNonNull("amount") ? data.get("amount").asLong() : null;

        // Solo se guarda una lista blanca de campos: nada sensible llega a la base de datos.
        Map<String, Object> sanitized = new LinkedHashMap<>();
        sanitized.put("id", eventId);
        sanitized.put("type", text(root, "type"));
        sanitized.put("payment_id", text(data, "id"));
        sanitized.put("reference", text(data, "reference"));
        sanitized.put("status", providerStatus);
        sanitized.put("amount", amount);
        sanitized.put("currency", text(data, "currency"));

        return new NewEvent(provider, eventId, text(data, "reference"), text(data, "id"),
                text(root, "type") == null ? "unknown" : text(root, "type"),
                mapped == null ? null : mapped.name(), amount, text(data, "currency"),
                json.writeValueAsString(sanitized));
    }

    /**
     * Clave de deduplicación del inbox (supuesto S4): el id del evento del proveedor o, si no lo
     * envía, un hash de (pago, estado). Un mismo estado repetido para el mismo pago no tiene
     * efecto adicional, así que colapsarlos es seguro.
     */
    static String eventKey(String eventId, String providerPaymentId, String providerStatus) {
        if (eventId != null && !eventId.isBlank()) {
            return eventId;
        }
        if (providerPaymentId == null || providerStatus == null) {
            throw new IllegalArgumentException("evento sin id y sin (pago, estado)");
        }
        return "sha256:" + Hashing.sha256Hex(providerPaymentId + "|" + providerStatus);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
