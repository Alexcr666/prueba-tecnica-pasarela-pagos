package com.pagos.payments.adapter.web;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifica autenticidad, integridad y frescura de un webhook (sección 10).
 *
 * <ul>
 *   <li>HMAC-SHA256 sobre {@code timestamp + "." + cuerpo crudo}: firmar el cuerpo exacto
 *       recibido, no uno re-serializado.</li>
 *   <li>Comparación en tiempo constante ({@link MessageDigest#isEqual}).</li>
 *   <li>Ventana de tolerancia del timestamp contra replay; dentro de la ventana, el UNIQUE del
 *       inbox neutraliza la repetición.</li>
 *   <li>Varios secretos vigentes para rotar sin cortar la recepción.</li>
 * </ul>
 */
public class WebhookSignatureVerifier {

    private final List<byte[]> secrets;
    private final Duration tolerance;

    public WebhookSignatureVerifier(List<String> secrets, Duration tolerance) {
        this.secrets = secrets.stream().map(s -> s.trim().getBytes(StandardCharsets.UTF_8)).toList();
        this.tolerance = tolerance;
    }

    public boolean verify(String timestampHeader, String signatureHeader, String rawBody, Instant now) {
        if (timestampHeader == null || signatureHeader == null || rawBody == null) {
            return false;
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        Duration skew;
        try {
            skew = Duration.between(Instant.ofEpochSecond(epochSeconds), now).abs();
        } catch (java.time.DateTimeException | ArithmeticException outOfRange) {
            return false; // timestamp absurdo: 401, no 500
        }
        if (skew.compareTo(tolerance) > 0) {
            return false;
        }
        byte[] received;
        try {
            received = HexFormat.of().parseHex(signatureHeader.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] signedPayload = (timestampHeader.trim() + "." + rawBody).getBytes(StandardCharsets.UTF_8);
        for (byte[] secret : secrets) {
            if (MessageDigest.isEqual(hmac(secret, signedPayload), received)) {
                return true;
            }
        }
        return false;
    }

    public static String sign(String secret, String timestamp, String rawBody) {
        byte[] mac = hmac(secret.getBytes(StandardCharsets.UTF_8),
                (timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(mac);
    }

    private static byte[] hmac(byte[] secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
