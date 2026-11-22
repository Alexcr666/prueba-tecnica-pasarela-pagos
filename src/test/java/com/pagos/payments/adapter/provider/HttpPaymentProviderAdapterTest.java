package com.pagos.payments.adapter.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pagos.payments.application.port.PaymentProviderPort.CreatePaymentRequest;
import com.pagos.payments.application.port.PaymentProviderPort.CreateResult;
import com.pagos.payments.application.port.PaymentProviderPort.LookupResult;
import com.pagos.payments.config.PaymentsProperties;
import com.pagos.payments.domain.PaymentStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Política de resiliencia hacia el proveedor (sección 9.1), con el proveedor simulado en WireMock. */
class HttpPaymentProviderAdapterTest {

    private WireMockServer provider;
    private static final CreatePaymentRequest REQUEST =
            new CreatePaymentRequest("ref-1", 150_000, "COP", "https://ret", "https://notify");

    @BeforeEach
    void start() {
        provider = new WireMockServer(wireMockConfig().dynamicPort());
        provider.start();
    }

    @AfterEach
    void stop() {
        provider.stop();
    }

    private HttpPaymentProviderAdapter adapter(String baseUrl) {
        var props = new PaymentsProperties(30,
                new PaymentsProperties.Provider("DEMO", baseUrl, "key", Duration.ofMillis(500),
                        Duration.ofMillis(1500), Duration.ofMillis(1500), "https://ret", "https://notify"),
                null, null, null, null, null);
        return new HttpPaymentProviderAdapter(props, new SimpleMeterRegistry());
    }

    @Test
    void acceptedPaymentReturnsRedirectAndSendsReferenceAsIdempotencyKey() {
        provider.stubFor(post("/v1/payments").willReturn(okJson(
                "{\"id\":\"pay_1\",\"status\":\"PENDING\",\"redirect_url\":\"https://pg/checkout/1\"}")));

        CreateResult result = adapter(provider.baseUrl()).create(REQUEST);

        assertThat(result).isEqualTo(new CreateResult.Accepted("pay_1", "https://pg/checkout/1", PaymentStatus.PENDING));
        provider.verify(postRequestedFor(urlEqualTo("/v1/payments"))
                .withHeader("Idempotency-Key", equalTo("ref-1"))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("ref-1"))));
    }

    @Test
    void readTimeoutIsUncertainAndIsNeverRetried() {
        provider.stubFor(post("/v1/payments").willReturn(okJson("{}").withFixedDelay(3_000)));

        CreateResult result = adapter(provider.baseUrl()).create(REQUEST);

        assertThat(result).isInstanceOf(CreateResult.Uncertain.class);
        provider.verify(1, postRequestedFor(urlEqualTo("/v1/payments")));
    }

    @Test
    void serverErrorIsUncertainAndIsNeverRetried() {
        provider.stubFor(post("/v1/payments").willReturn(serverError()));

        assertThat(adapter(provider.baseUrl()).create(REQUEST)).isInstanceOf(CreateResult.Uncertain.class);
        provider.verify(1, postRequestedFor(urlEqualTo("/v1/payments")));
    }

    @Test
    void connectionRefusedMeansNotCreated() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        CreateResult result = adapter("http://localhost:" + closedPort).create(REQUEST);
        assertThat(result).isInstanceOf(CreateResult.NotCreated.class);
    }

    @Test
    void clientErrorMeansNotCreated() {
        provider.stubFor(post("/v1/payments").willReturn(badRequest()));
        assertThat(adapter(provider.baseUrl()).create(REQUEST)).isInstanceOf(CreateResult.NotCreated.class);
    }

    @Test
    void conflictOnReferenceIsUncertain() {
        provider.stubFor(post("/v1/payments").willReturn(aResponse().withStatus(409)));
        assertThat(adapter(provider.baseUrl()).create(REQUEST)).isInstanceOf(CreateResult.Uncertain.class);
    }

    @Test
    void synchronousDeclineIsReported() {
        provider.stubFor(post("/v1/payments").willReturn(okJson("{\"id\":\"pay_2\",\"status\":\"DECLINED\"}")));
        assertThat(adapter(provider.baseUrl()).create(REQUEST)).isInstanceOf(CreateResult.Declined.class);
    }

    @Test
    void lookupFindsPaymentByReference() {
        provider.stubFor(get(urlPathEqualTo("/v1/payments")).withQueryParam("reference", equalTo("ref-1"))
                .willReturn(okJson("{\"id\":\"pay_1\",\"status\":\"APPROVED\",\"amount\":150000,\"currency\":\"COP\"}")));

        LookupResult result = adapter(provider.baseUrl()).findByReference("ref-1");

        assertThat(result).isEqualTo(new LookupResult.Found("pay_1", PaymentStatus.APPROVED, 150_000L, "COP"));
    }

    @Test
    void lookupNotFound() {
        provider.stubFor(get(urlPathEqualTo("/v1/payments")).willReturn(notFound()));
        assertThat(adapter(provider.baseUrl()).findByReference("ref-x")).isInstanceOf(LookupResult.NotFound.class);
    }

    @Test
    void lookupRetriesTransientFailuresBecauseReadingIsSafe() {
        provider.stubFor(get(urlPathEqualTo("/v1/payments")).willReturn(serverError()));

        assertThat(adapter(provider.baseUrl()).findByReference("ref-1")).isInstanceOf(LookupResult.Unavailable.class);
        provider.verify(3, getRequestedFor(urlPathEqualTo("/v1/payments")));
    }
}
