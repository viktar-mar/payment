package dev.genericpayments.platform.payments.application.service;

/** A watermark comparison is only one prerequisite; ownership and receipt consumption are separate. */
public final class ClientAttemptPolicy {
    private ClientAttemptPolicy() {}
    public static boolean isNewFailure(String observed,String watermark) {
        return observed!=null&&!observed.equals(watermark);
    }
}
