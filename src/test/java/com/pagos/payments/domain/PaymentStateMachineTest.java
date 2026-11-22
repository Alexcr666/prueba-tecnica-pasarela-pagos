package com.pagos.payments.domain;

import static com.pagos.payments.domain.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Verifica cada par origen → destino contra la tabla 5.1 del diseño. */
class PaymentStateMachineTest {

    private static final Map<PaymentStatus, Set<PaymentStatus>> SPEC = Map.of(
            CREATED, EnumSet.noneOf(PaymentStatus.class),
            PENDING, EnumSet.of(CREATED, UNKNOWN),
            UNKNOWN, EnumSet.of(CREATED),
            APPROVED, EnumSet.of(CREATED, PENDING, UNKNOWN, FAILED),
            DECLINED, EnumSet.of(CREATED, PENDING, UNKNOWN, FAILED),
            EXPIRED, EnumSet.of(CREATED, PENDING, UNKNOWN, FAILED),
            FAILED, EnumSet.of(CREATED, UNKNOWN));

    static Stream<Arguments> allPairs() {
        return Stream.of(PaymentStatus.values())
                .flatMap(from -> Stream.of(PaymentStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void everyPairMatchesTheSpecification(PaymentStatus from, PaymentStatus to) {
        assertThat(PaymentStateMachine.canTransition(from, to)).isEqualTo(SPEC.get(to).contains(from));
    }

    @Test
    void providerTerminalStatesNeverMove() {
        for (PaymentStatus terminal : EnumSet.of(APPROVED, DECLINED, EXPIRED)) {
            for (PaymentStatus target : PaymentStatus.values()) {
                assertThat(PaymentStateMachine.canTransition(terminal, target))
                        .as("%s -> %s", terminal, target).isFalse();
            }
        }
    }

    @Test
    void stateNeverGoesBackToPendingOnceResolved() {
        assertThat(PaymentStateMachine.allowedSourcesOf(PENDING)).doesNotContain(APPROVED, DECLINED, EXPIRED, FAILED);
    }

    @Test
    void inferredFailureCanBeCorrectedByProviderEvidence() {
        assertThat(PaymentStateMachine.canTransition(FAILED, APPROVED)).isTrue();
    }

    @Test
    void activeStatesAreExactlyThoseThatBlockANewAttempt() {
        assertThat(EnumSet.allOf(PaymentStatus.class).stream().filter(PaymentStatus::isActive))
                .containsExactlyInAnyOrder(CREATED, PENDING, UNKNOWN);
    }
}
