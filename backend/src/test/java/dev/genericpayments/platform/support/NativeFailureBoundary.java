package dev.genericpayments.platform.support;

import org.junit.jupiter.api.extension.*;

/** Provider messages and assertion values may include a session capability. Publish only source locations. */
public final class NativeFailureBoundary implements TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {
    @Override public void handleTestExecutionException(ExtensionContext context, Throwable error) { throw safe(error); }
    @Override public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable error) { throw safe(error); }
    @Override public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable error) { throw safe(error); }
    private static final java.util.Set<String> BROWSER_STEPS = java.util.Set.of("PORTAL_INPUT","PORTAL_BROWSER_LAUNCH",
            "PORTAL_HOSTED_DOCUMENT","PORTAL_RETURN_LINK","PORTAL_RETURN_COMMIT","PORTAL_CLEANUP","PORTAL_NO_RESULT");
    private static AssertionError safe(Throwable error) {
        var code = new StringBuilder("NATIVE_CASE_FAILED");
        if ("PORTAL_CONFIGURATION_CLEANUP".equals(error.getMessage()) ||
                java.util.Arrays.stream(error.getSuppressed()).anyMatch(e -> "PORTAL_CONFIGURATION_CLEANUP".equals(e.getMessage()))) {
            code.append(" PORTAL_CONFIGURATION_CLEANUP");
        }
        for(int i=0; error!=null && i<5; i++,error=error.getCause()) {
            code.append(' ').append(error.getClass().getSimpleName());
            if (error instanceof AssertionError && BROWSER_STEPS.contains(java.util.Objects.toString(error.getMessage(),"")))
                code.append(' ').append(error.getMessage());
            int count = 0;
            for(var frame:error.getStackTrace()) {
                if(frame.getClassName().startsWith("dev.genericpayments.")) {
                    code.append(' ').append(frame.getFileName()).append(':').append(frame.getLineNumber());
                    if (++count == 6) break;
                }
            }
            if(error instanceof com.stripe.exception.StripeException failure) {
                code.append(" HTTP_").append(failure.getStatusCode());
                var detail=failure.getStripeError();
                if(detail!=null && java.util.Set.of("parameter_unknown","parameter_missing","resource_missing","card_declined","invoice_no_payment_method_types","parameter_invalid_empty").contains(java.util.Objects.toString(detail.getCode(),"")))
                    code.append(' ').append(detail.getCode());
            }
        }
        return new AssertionError(code.toString());
    }
}
