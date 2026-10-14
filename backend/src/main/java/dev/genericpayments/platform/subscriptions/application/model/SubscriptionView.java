package dev.genericpayments.platform.subscriptions.application.model;
import dev.genericpayments.platform.subscriptions.domain.*;
import java.time.Instant;
import java.util.*;
public record SubscriptionView(Subscription subscription,AccessDecision access,Set<PermittedAction> permittedActions,NextAmount nextAmount,DebtView debt,PendingOperation pendingOperation) {
 public enum PermittedAction { CHANGE_PLAN,CLEAR_CHANGE,CANCEL_PERIOD_END,CANCEL_IMMEDIATE,WITHDRAW_CANCELLATION,SETUP_METHOD,RECOVER_INVOICE,RESUME,OPEN_PORTAL }
 public record NextAmount(long amountMinor,String currency,boolean taxFinal) {}
 public record DebtView(UUID billingRecordId,Subscription.Period period,Instant graceDeadline,long amountDueMinor,String currency) {}
 public record PendingOperation(UUID operationId,SubscriptionOperation.Kind kind,SubscriptionOperation.Status status) {}
 public SubscriptionView { permittedActions=Set.copyOf(permittedActions); }
}
