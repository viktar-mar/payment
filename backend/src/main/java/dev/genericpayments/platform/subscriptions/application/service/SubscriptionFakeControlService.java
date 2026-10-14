package dev.genericpayments.platform.subscriptions.application.service;
import dev.genericpayments.platform.subscriptions.application.port.in.ControlFakeSubscriptionUseCase;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.subscriptions.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.admin.application.port.out.AuditLogPort;
import dev.genericpayments.platform.admin.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import java.time.Clock;import java.util.*;
import org.springframework.stereotype.Service;import org.springframework.context.annotation.Profile;
@Service @Profile("local")
public class SubscriptionFakeControlService implements ControlFakeSubscriptionUseCase {
 private final SubscriptionTransactionPort tx;private final SubscriptionAuthorityPort authority;private final SubscriptionStore subscriptions;
 private final LoadPaymentAccountPort accounts;private final FakeSubscriptionControlPort fake;private final SubscriptionAdminReceiptPort receipts;private final AuditLogPort audit;private final Clock clock;
 public SubscriptionFakeControlService(SubscriptionTransactionPort tx,SubscriptionAuthorityPort authority,SubscriptionStore subscriptions,LoadPaymentAccountPort accounts,FakeSubscriptionControlPort fake,SubscriptionAdminReceiptPort receipts,AuditLogPort audit,Clock clock){this.tx=tx;this.authority=authority;this.subscriptions=subscriptions;this.accounts=accounts;this.fake=fake;this.receipts=receipts;this.audit=audit;this.clock=clock;}
 public Result control(Command c){return tx.required(()->{
  if(!(c.context().actor() instanceof SubscriptionActor.Admin a))throw new PaymentApplicationException("AUTHENTICATION_FAILED",401,"Authentication failed");
  authority.revalidate(c.context(),true);var s=subscriptions.lock(c.context().siteId(),c.subscriptionId());
  if(!s.customerId().equals(c.context().customerId()))throw SubscriptionQueryService.missing();
  var account=accounts.findById(s.paymentAccountId()).orElseThrow(SubscriptionQueryService::missing);
  if(account.provider()!=PaymentProviderType.FAKE)throw SubscriptionRuleErrors.conflict();
  if(c.action()==null||c.action()==Action.SCENARIO&&(c.scenario()==null||c.until()!=null)||c.action()==Action.COMPLETE_ACTION&&(c.scenario()!=null||c.until()!=null)||c.action()==Action.ADVANCE&&(c.scenario()!=null||c.until()==null))throw invalid();
  String fp=c.subscriptionId()+"|"+c.action()+"|"+c.scenario()+"|"+c.until();
  return receipts.once(s.siteId(),a.admin().id().value(),"FAKE_CONTROL",c.context().idempotencyKey(),fp,Result.class,()->{
   if(c.action()==Action.ADVANCE&&(c.until().isBefore(clock.instant())||c.until().isAfter(clock.instant().plusSeconds(366L*86400))))throw invalid();
   switch(c.action()){case SCENARIO->fake.scenario(account.id(),s.id(),c.scenario());case COMPLETE_ACTION->fake.completeAction(account.id(),s.id());case ADVANCE->fake.advance(account.id(),c.until());}
   audit.append(new AuditEntry(UUID.randomUUID(),"ADMIN",a.admin().id().value().toString(),AuditAction.SUBSCRIPTION_FAKE_CONTROL,"subscription",s.id().toString(),c.context().requestId(),null,Map.of("action",c.action().name()),clock.instant()));
   return new Result(s.id(),account.id().value(),c.action(),c.scenario(),c.until(),true);
  });
 });}
 private static PaymentApplicationException invalid(){return new PaymentApplicationException("INVALID_REQUEST",400,"Invalid Fake subscription control");}
}
