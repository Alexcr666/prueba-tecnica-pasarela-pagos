package com.pagos.payments.adapter.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pagos.payments.application.port.PaymentProviderPort;
import com.pagos.payments.config.PaymentsProperties;
import com.pagos.payments.domain.PaymentStatus;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Adaptador HTTP del proveedor con la política de resiliencia de la sección 9.1.
 *
 * <p>Regla central: solo se reintenta lo que es seguro. Un fallo de CONEXIÓN significa que la
 * petición no salió, así que se reintenta con la misma referencia. Un timeout de LECTURA o un 5xx
 * significan que no sabemos si el cobro se creó: se devuelve {@code Uncertain} y lo resuelve la
 * conciliación.
 */
@Component
public class HttpPaymentProviderAdapter implements PaymentProviderPort {

    private static final Logger log = LoggerFactory.getLogger(HttpPaymentProviderAdapter.class);

    private final PaymentsProperties.Provider config;
    private final RestClient createClient;
    private final RestClient queryClient;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final Retry createRetry;
    private final Retry queryRetry;

    public HttpPaymentProviderAdapter(PaymentsProperties props, MeterRegistry meterRegistry) {
        this.config = props.provider();
        // HTTP/1.1 explícito: evita la negociación h2c y hace predecible el manejo de timeouts.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.connectTimeout())
                .build();
        this.createClient = restClient(httpClient, config.createReadTimeout());
        this.queryClient = restClient(httpClient, config.queryReadTimeout());

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(100)
                .minimumNumberOfCalls(20)
                .failureRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .slowCallRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(10)
                // Un 4xx es un error nuestro o un rechazo, no una caída del proveedor.
                .ignoreExceptions(HttpClientErrorException.class)
                .build());
        this.circuitBreaker = registry.circuitBreaker("payment-provider");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);

        // Con virtual threads no hay pool que limite la concurrencia: el bulkhead protege al proveedor.
        this.bulkhead = Bulkhead.of("payment-provider", BulkheadConfig.custom()
                .maxConcurrentCalls(100).maxWaitDuration(Duration.ofMillis(50)).build());

        this.createRetry = Retry.of("provider-create", RetryConfig.custom()
                .maxAttempts(3) // 1 + 2 reintentos
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(200), 2.0, 0.5))
                .retryOnException(HttpPaymentProviderAdapter::isConnectFailure)
                .build());
        this.queryRetry = Retry.of("provider-query", RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(250), 2.0, 0.5))
                // Consultar es una lectura: reintentar es seguro ante cualquier fallo transitorio.
                .retryOnException(e -> e instanceof ResourceAccessException || e instanceof HttpServerErrorException)
                .build());
    }

    @Override
    public String name() {
        return config.name();
    }

    @Override
    public boolean isAvailable() {
        return circuitBreaker.getState() != CircuitBreaker.State.OPEN
                && circuitBreaker.getState() != CircuitBreaker.State.FORCED_OPEN;
    }

    @Override
    public CreateResult create(CreatePaymentRequest request) {
        Supplier<ProviderPayment> call = () -> createClient.post()
                .uri(config.baseUrl() + "/v1/payments")
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Idempotency-Key", request.merchantReference())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "reference", request.merchantReference(),
                        "amount", request.amountMinor(),
                        "currency", request.currency(),
                        "return_url", request.returnUrl(),
                        "notification_url", request.notificationUrl()))
                .retrieve()
                .body(ProviderPayment.class);
        try {
            ProviderPayment payment = decorate(call, createRetry).get();
            PaymentStatus status = ProviderStatusMapper.map(payment.status());
            if (status == PaymentStatus.DECLINED) {
                return new CreateResult.Declined(payment.id(), "declined by provider");
            }
            if (status == null) {
                return new CreateResult.Uncertain("unmapped provider status " + payment.status());
            }
            return new CreateResult.Accepted(payment.id(), payment.redirectUrl(), status);
        } catch (CallNotPermittedException | BulkheadFullException rejectedLocally) {
            return new CreateResult.NotCreated("provider protection: " + rejectedLocally.getClass().getSimpleName());
        } catch (HttpClientErrorException.Conflict conflict) {
            // Ya existe un pago con esta referencia: la conciliación lo encontrará.
            return new CreateResult.Uncertain("409 conflict on reference");
        } catch (HttpClientErrorException clientError) {
            return new CreateResult.NotCreated("provider rejected request: " + clientError.getStatusCode());
        } catch (HttpServerErrorException serverError) {
            return new CreateResult.Uncertain("provider " + serverError.getStatusCode());
        } catch (ResourceAccessException io) {
            if (isConnectFailure(io)) {
                return new CreateResult.NotCreated("connection failure: " + rootMessage(io));
            }
            return new CreateResult.Uncertain("read failure: " + rootMessage(io));
        }
    }

    @Override
    public LookupResult findByReference(String merchantReference) {
        Supplier<ProviderPayment> call = () -> queryClient.get()
                .uri(config.baseUrl() + "/v1/payments?reference={ref}", merchantReference)
                .header("Authorization", "Bearer " + config.apiKey())
                .retrieve()
                .body(ProviderPayment.class);
        try {
            ProviderPayment payment = decorate(call, queryRetry).get();
            PaymentStatus status = ProviderStatusMapper.map(payment.status());
            if (status == null) {
                return new LookupResult.Unavailable("unmapped provider status " + payment.status());
            }
            return new LookupResult.Found(payment.id(), status, payment.amount(), payment.currency());
        } catch (HttpClientErrorException.NotFound notFound) {
            return new LookupResult.NotFound();
        } catch (RuntimeException e) {
            log.warn("Consulta al proveedor fallida reference={} error={}", merchantReference, rootMessage(e));
            return new LookupResult.Unavailable(rootMessage(e));
        }
    }

    /**
     * Orden de decoración: Retry( Bulkhead( CircuitBreaker( llamada ) ) ). El bulkhead va fuera del
     * circuit breaker: una saturación local no debe contarse como caída del proveedor.
     */
    private <T> Supplier<T> decorate(Supplier<T> call, Retry retry) {
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(circuitBreaker, call);
        Supplier<T> limited = Bulkhead.decorateSupplier(bulkhead, guarded);
        return Retry.decorateSupplier(retry, limited);
    }

    /** La petición no llegó a salir: conexión rechazada, timeout de conexión o DNS. */
    static boolean isConnectFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException
                    || t instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }

    private static RestClient restClient(HttpClient httpClient, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** Contrato asumido del proveedor (supuesto S2). */
    record ProviderPayment(String id, String reference, String status,
                           @JsonProperty("redirect_url") String redirectUrl,
                           Long amount, String currency) {
    }
}
