package dev.genericpayments.acceptance.runtime;

import com.google.gson.JsonObject;

/** Fixed private scenario surface; all targets and authority remain retained by the owner. */
public interface OwnedBillingFailedRenewalOperations extends OwnedBillingFirstPaymentRuntime.Operations {
    JsonObject boundary();JsonObject armDecline();JsonObject failedRenewal();
    JsonObject graceLastSecond();JsonObject graceAt();JsonObject graceAfter();
    JsonObject prepareMethod();JsonObject selectMethod();JsonObject confirmMethod();JsonObject applyMethod();
    JsonObject confirmRecovery();JsonObject observeRecovery();String failureStage();
}
