package com.pagos.payments.support;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generador de UUID versión 7 (RFC 9562): 48 bits de timestamp en milisegundos + aleatorio.
 * Ordenable por tiempo, así que no fragmenta los índices B-tree como un UUIDv4.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static String next() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb).toString();
    }
}
