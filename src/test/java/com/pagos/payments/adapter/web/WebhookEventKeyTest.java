package com.pagos.payments.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Clave de deduplicación del inbox cuando el proveedor no envía id de evento (supuesto S4). */
class WebhookEventKeyTest {

    @Test
    void usesProviderEventIdWhenPresent() {
        assertThat(WebhookController.eventKey("evt_1", "pay_1", "APPROVED")).isEqualTo("evt_1");
    }

    @Test
    void sameStatusForSamePaymentWithoutIdCollapsesToSameKey() {
        String first = WebhookController.eventKey(null, "pay_1", "APPROVED");
        assertThat(WebhookController.eventKey("  ", "pay_1", "APPROVED")).isEqualTo(first);
        assertThat(first).startsWith("sha256:").hasSize("sha256:".length() + 64);
    }

    @Test
    void differentStatusGivesDifferentKey() {
        assertThat(WebhookController.eventKey(null, "pay_1", "PENDING"))
                .isNotEqualTo(WebhookController.eventKey(null, "pay_1", "APPROVED"));
    }

    @Test
    void withoutIdNorPaymentDataIsRejected() {
        assertThatThrownBy(() -> WebhookController.eventKey(null, null, "APPROVED"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
