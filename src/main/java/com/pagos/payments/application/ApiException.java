package com.pagos.payments.application;

/** Error de negocio con su código HTTP. Lo traduce {@code ApiExceptionHandler}. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "NOT_FOUND", what + " no existe");
    }

    public static ApiException idempotencyMismatch() {
        return new ApiException(422, "IDEMPOTENCY_KEY_REUSED",
                "La Idempotency-Key ya se usó con un cuerpo distinto");
    }

    public static ApiException orderNotPayable(String status) {
        return new ApiException(409, "ORDER_NOT_PAYABLE", "La orden no admite pagos en estado " + status);
    }

    public static ApiException activePaymentExists(String attemptId) {
        return new ApiException(409, "ACTIVE_PAYMENT_EXISTS",
                "La orden ya tiene un intento de pago activo: " + attemptId);
    }

    public static ApiException providerUnavailable() {
        return new ApiException(503, "PROVIDER_UNAVAILABLE",
                "La pasarela no está disponible; intenta de nuevo en unos minutos");
    }
}
