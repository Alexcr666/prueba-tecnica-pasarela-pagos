package com.pagos.payments.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exchange de eventos de pagos. Cada consumidor declara su propia quorum queue con
 * {@code x-delivery-limit}, reintento con retardo (RabbitMQ 4.3) y DLQ; eso es responsabilidad
 * de cada sistema consumidor, no de este servicio.
 */
@Configuration
public class RabbitConfig {

    @Bean
    TopicExchange paymentsExchange(PaymentsProperties props) {
        return new TopicExchange(props.outbox().exchange(), true, false);
    }
}
