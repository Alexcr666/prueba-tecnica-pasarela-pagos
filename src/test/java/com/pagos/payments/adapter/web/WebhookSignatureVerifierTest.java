package com.pagos.payments.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

    private static final String BODY = "{\"id\":\"evt_1\",\"data\":{\"status\":\"APPROVED\"}}";
    private final Instant now = Instant.parse("2026-11-20T15:00:00Z");
    private final String ts = String.valueOf(now.getEpochSecond());
    private final WebhookSignatureVerifier verifier =
            new WebhookSignatureVerifier(List.of("current-secret", "previous-secret"), Duration.ofMinutes(5));

    @Test
    void acceptsValidSignature() {
        String sig = WebhookSignatureVerifier.sign("current-secret", ts, BODY);
        assertThat(verifier.verify(ts, sig, BODY, now)).isTrue();
    }

    @Test
    void acceptsSecondSecretDuringRotation() {
        String sig = WebhookSignatureVerifier.sign("previous-secret", ts, BODY);
        assertThat(verifier.verify(ts, sig, BODY, now)).isTrue();
    }

    @Test
    void rejectsTamperedBody() {
        String sig = WebhookSignatureVerifier.sign("current-secret", ts, BODY);
        assertThat(verifier.verify(ts, sig, BODY.replace("APPROVED", "DECLINED"), now)).isFalse();
    }

    @Test
    void rejectsUnknownSecret() {
        String sig = WebhookSignatureVerifier.sign("attacker-secret", ts, BODY);
        assertThat(verifier.verify(ts, sig, BODY, now)).isFalse();
    }

    @Test
    void rejectsReplayOutsideTolerance() {
        String oldTs = String.valueOf(now.minus(Duration.ofMinutes(6)).getEpochSecond());
        String sig = WebhookSignatureVerifier.sign("current-secret", oldTs, BODY);
        assertThat(verifier.verify(oldTs, sig, BODY, now)).isFalse();
    }

    @Test
    void rejectsMissingOrMalformedHeaders() {
        assertThat(verifier.verify(null, "ab", BODY, now)).isFalse();
        assertThat(verifier.verify(ts, null, BODY, now)).isFalse();
        assertThat(verifier.verify("not-a-number", "ab", BODY, now)).isFalse();
        assertThat(verifier.verify(ts, "zz-not-hex", BODY, now)).isFalse();
        assertThat(verifier.verify(String.valueOf(Long.MAX_VALUE), "ab", BODY, now)).isFalse();
    }
}
