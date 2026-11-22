package com.pagos.payments;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pagos.payments.adapter.web.WebhookController;
import com.pagos.payments.adapter.web.WebhookSignatureVerifier;
import com.pagos.payments.application.ApiException;
import com.pagos.payments.application.EventPublisher;
import com.pagos.payments.application.InboxEventHandler;
import com.pagos.payments.application.InboxProcessor;
import com.pagos.payments.application.OrderService;
import com.pagos.payments.application.PaymentInitiationService;
import com.pagos.payments.application.ReconciliationService;
import com.pagos.payments.domain.OrderStatus;
import com.pagos.payments.domain.PaymentAttempt;
import com.pagos.payments.domain.PaymentStatus;
import com.pagos.payments.persistence.HistoryRepository;
import com.pagos.payments.persistence.OrderRepository;
import com.pagos.payments.persistence.OutboxRepository;
import com.pagos.payments.persistence.PaymentAttemptRepository;
import com.pagos.payments.persistence.ProviderEventRepository;
import com.pagos.payments.support.UuidV7;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/**
 * Pruebas de integración contra Oracle real (Testcontainers) con el proveedor simulado (WireMock).
 * Cubren los escenarios de la sección 12 del diseño. Se omiten si no hay Docker disponible.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "payments.scheduling.enabled=false",
        "payments.outbox.relay-enabled=false",
        "payments.webhook.secrets=test-secret",
        "payments.reconciliation.first-check-seconds=0",
        "payments.provider.create-read-timeout=500ms",
        "payments.provider.query-read-timeout=500ms"
})
class PaymentFlowIT {

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withStartupTimeout(java.time.Duration.ofMinutes(5));

    static final WireMockServer PROVIDER = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        PROVIDER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ORACLE::getJdbcUrl);
        registry.add("spring.datasource.username", ORACLE::getUsername);
        registry.add("spring.datasource.password", ORACLE::getPassword);
        registry.add("payments.provider.base-url", PROVIDER::baseUrl);
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.stop();
    }

    @Autowired OrderService orders;
    @Autowired PaymentInitiationService payments;
    @Autowired WebhookController webhooks;
    @Autowired InboxProcessor inbox;
    @Autowired InboxEventHandler inboxHandler;
    @Autowired ReconciliationService reconciliation;
    @Autowired PaymentAttemptRepository attempts;
    @Autowired OrderRepository orderRepository;
    @Autowired HistoryRepository history;
    @Autowired OutboxRepository outbox;
    @Autowired ProviderEventRepository events;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void resetProvider() {
        PROVIDER.resetAll();
    }

    // ---------------------------------------------------------------- 7.1 flujo exitoso

    @Test
    void happyPath_webhookApprovesPaymentAndPaysOrderExactlyOnce() {
        PaymentAttempt attempt = startPendingPayment();

        assertThat(sendWebhook("evt-ok-" + attempt.id(), attempt.id(), "APPROVED", 150_000)).isFalse();
        inbox.processBatch();

        assertThat(attempts.findById(attempt.id()).orElseThrow().status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(orderRepository.findById(attempt.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
        assertThat(outbox.countByAggregateAndType(attempt.orderId(), EventPublisher.ORDER_PAID)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 7.3 duplicado y concurrencia

    @Test
    void sameWebhookDeliveredTwiceConcurrently_hasEffectOnce() throws Exception {
        PaymentAttempt attempt = startPendingPayment();
        String eventId = "evt-dup-" + attempt.id();

        List<Boolean> duplicates = runConcurrently(
                () -> sendWebhook(eventId, attempt.id(), "APPROVED", 150_000),
                () -> sendWebhook(eventId, attempt.id(), "APPROVED", 150_000));
        inbox.processBatch();

        assertThat(duplicates).containsExactlyInAnyOrder(true, false);
        // CREATED + PENDING + APPROVED: una sola fila de historial para APPROVED.
        assertThat(history.countForAttempt(attempt.id())).isEqualTo(3);
        assertThat(outbox.countByAggregateAndType(attempt.orderId(), EventPublisher.ORDER_PAID)).isEqualTo(1);
    }

    @Test
    void differentEventsForSamePaymentProcessedConcurrently_endApproved() throws Exception {
        PaymentAttempt attempt = startCreatedOnly();
        sendWebhook("evt-a-" + attempt.id(), attempt.id(), "PENDING", 150_000);
        sendWebhook("evt-b-" + attempt.id(), attempt.id(), "APPROVED", 150_000);
        List<Long> ids = jdbc.sql("SELECT id FROM provider_event WHERE merchant_reference = :r ORDER BY id")
                .param("r", attempt.id()).query(Long.class).list();

        runConcurrently(() -> { inboxHandler.handle(ids.get(0)); return true; },
                () -> { inboxHandler.handle(ids.get(1)); return true; });

        // Sea cual sea el orden real, el estado final es APPROVED y nunca retrocede.
        assertThat(attempts.findById(attempt.id()).orElseThrow().status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(orderRepository.findById(attempt.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void lateEventOutOfOrder_isIgnoredAsStale() {
        PaymentAttempt attempt = startPendingPayment();
        sendWebhook("evt-42-" + attempt.id(), attempt.id(), "APPROVED", 150_000);
        inbox.processBatch();

        sendWebhook("evt-41-" + attempt.id(), attempt.id(), "PENDING", 150_000);
        inbox.processBatch();

        assertThat(attempts.findById(attempt.id()).orElseThrow().status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(events.processingStatusOf("DEMO", "evt-41-" + attempt.id())).isEqualTo("IGNORED_STALE");
    }

    @Test
    void approvedWithDifferentAmount_sendsOrderToReview() {
        PaymentAttempt attempt = startPendingPayment();
        sendWebhook("evt-amt-" + attempt.id(), attempt.id(), "APPROVED", 1_000);
        inbox.processBatch();

        assertThat(orderRepository.findById(attempt.orderId()).orElseThrow().status())
                .isEqualTo(OrderStatus.REQUIRES_REVIEW);
    }

    // ---------------------------------------------------------------- 7.2 timeout, UNKNOWN y conciliación

    @Test
    void timeoutLeavesUnknown_blocksSecondAttempt_andReconciliationResolves() {
        String orderId = newOrder();
        PROVIDER.stubFor(post("/v1/payments").willReturn(okJson("{}").withFixedDelay(2_000)));

        PaymentAttempt attempt = payments.initiate(orderId, UuidV7.next()).attempt();
        assertThat(attempt.status()).isEqualTo(PaymentStatus.UNKNOWN);

        // Otro clic con otra clave: el índice de intento activo impide un segundo cobro.
        assertThatThrownBy(() -> payments.initiate(orderId, UuidV7.next()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(409));

        PROVIDER.stubFor(get(urlPathEqualTo("/v1/payments")).withQueryParam("reference", equalTo(attempt.id()))
                .willReturn(okJson("{\"id\":\"pay-late\",\"status\":\"APPROVED\",\"amount\":150000,\"currency\":\"COP\"}")));
        reconciliation.runOnce();

        assertThat(attempts.findById(attempt.id()).orElseThrow().status()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(orderRepository.findById(orderId).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void sameIdempotencyKeyTwice_callsProviderOnceAndReturnsSameAttempt() {
        String orderId = newOrder();
        stubCreatePending();
        String key = UuidV7.next();

        var first = payments.initiate(orderId, key);
        var second = payments.initiate(orderId, key);

        assertThat(second.replay()).isTrue();
        assertThat(second.attempt().id()).isEqualTo(first.attempt().id());
        PROVIDER.verify(1, postRequestedFor(urlEqualTo("/v1/payments")));
    }

    // ---------------------------------------------------------------- utilidades

    private String newOrder() {
        return orders.create("customer-1", 150_000, "COP", UuidV7.next(), UuidV7.next()).order().id();
    }

    private void stubCreatePending() {
        PROVIDER.stubFor(post("/v1/payments").willReturn(okJson(
                "{\"id\":\"pay-" + UuidV7.next() + "\",\"status\":\"PENDING\",\"redirect_url\":\"https://pg/checkout\"}")));
    }

    private PaymentAttempt startPendingPayment() {
        String orderId = newOrder();
        stubCreatePending();
        PaymentAttempt attempt = payments.initiate(orderId, UuidV7.next()).attempt();
        assertThat(attempt.status()).isEqualTo(PaymentStatus.PENDING);
        return attempt;
    }

    /** Intento en CREATED (como si el proceso se hubiera caído antes de guardar la respuesta). */
    private PaymentAttempt startCreatedOnly() {
        String orderId = newOrder();
        String attemptId = UuidV7.next();
        attempts.insertCreated(attemptId, orderId, "DEMO", 150_000, "COP");
        return attempts.findById(attemptId).orElseThrow();
    }

    /** Envía un webhook firmado. Devuelve true si el receptor lo marcó como duplicado. */
    private boolean sendWebhook(String eventId, String reference, String status, long amount) {
        String body = """
                {"id":"%s","type":"payment.updated","data":{"id":"pay-%s","reference":"%s","status":"%s","amount":%d,"currency":"COP"}}
                """.formatted(eventId, reference, reference, status, amount).trim();
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String sig = WebhookSignatureVerifier.sign("test-secret", ts, body);
        var response = webhooks.receive("DEMO", ts, sig, body);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return (Boolean) response.getBody().get("duplicate");
    }

    @SafeVarargs
    private static <T> List<T> runConcurrently(Callable<T>... tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.length)) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}
