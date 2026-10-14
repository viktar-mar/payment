package dev.genericpayments.platform.payments.application.service;
import dev.genericpayments.platform.payments.application.model.*;
import dev.genericpayments.platform.payments.application.port.out.*;
import dev.genericpayments.platform.payments.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
/** Shared construction of safe journal facts and ownership validation. */
public final class PaymentPhaseSupport {
 private final AppendPaymentEventPort append;private final LoadPaymentEventPort events;private final DigestPort digest;
 private final LoadSitePort sites;private final LoadPaymentAccountPort accounts;private final Clock clock;
 public PaymentPhaseSupport(AppendPaymentEventPort append,LoadPaymentEventPort events,DigestPort digest,LoadSitePort sites,LoadPaymentAccountPort accounts,Clock clock){
  this.append=append;this.events=events;this.digest=digest;this.sites=sites;this.accounts=accounts;this.clock=clock;
 }
 Instant now(){return clock.instant();}
 void ownership(SiteId id,PaymentAccount account,String siteKey){
  var site=sites.findById(id).orElseThrow(()->new IllegalArgumentException("Payment Site unavailable"));
  if(site.status()!=SiteStatus.ACTIVE||!site.paymentAccountId().equals(account.id())||!site.key().value().equals(siteKey)
    ||!accounts.findById(account.id()).filter(account::equals).isPresent()||account.status()!=PaymentAccountStatus.ACTIVE)throw new IllegalArgumentException("Payment ownership mismatch");
 }
 UUID eventId(String key,String phase){return UUID.nameUUIDFromBytes((key+":"+phase).getBytes(StandardCharsets.UTF_8));}
 boolean hasEvent(Payment p,String key,String phase){var id=eventId(key,phase);return events.findByPayment(p.siteId(),p.id()).stream().anyMatch(e->e.id().equals(id));}
 void event(Payment p,String key,String phase,PaymentEventType type,String requestId,String idem,ProviderResult result){
  Instant now=now();String code=result==null?type.name():result.code().name();
  append.append(p.id(),seq -> new PaymentEvent(eventId(key,phase),p.id(),seq,p.siteId(),p.paymentAccountId(),type,PaymentEventSource.API,
   p.money(),null,p.providerPaymentReference(),now,now,requestId,idem,digest.sha256Hex(key+":"+phase+":"+type+":"+code),Map.of("result_code",code)));
 }
 Payment apply(Payment p,ProviderResult result,boolean confirmation){
  Instant now=now();if(result.reference()!=null)p=p.withProviderPaymentReference(result.reference(),now);
  return switch(result.kind()){
   case ACCEPTED -> p.recoverableDecline(now);
   case PROCESSING -> p.processing(now);
   case REQUIRES_ACTION -> p.requiresAction(now);
   case RECOVERABLE_DECLINE,TEMPORARILY_UNAVAILABLE -> p.recoverableDecline(now);
   case TERMINAL_DECLINE -> confirmation?p.terminalDecline(now):p.recoverableDecline(now);
   case UNKNOWN -> p;
  };
 }
 PaymentEventType type(ProviderResult result,boolean confirmation){return switch(result.kind()){
  case ACCEPTED,RECOVERABLE_DECLINE,TEMPORARILY_UNAVAILABLE -> PaymentEventType.PAYMENT_METHOD_REQUIRED;
  case PROCESSING -> PaymentEventType.PAYMENT_PROCESSING;
  case REQUIRES_ACTION -> PaymentEventType.CUSTOMER_ACTION_REQUIRED;
  case TERMINAL_DECLINE -> confirmation?PaymentEventType.PAYMENT_FAILED:PaymentEventType.PAYMENT_METHOD_REQUIRED;
  case UNKNOWN -> PaymentEventType.PROVIDER_RESULT_UNKNOWN;
 };}
 public record Change(Payment payment,PaymentStatus previous,boolean changed){}
}
