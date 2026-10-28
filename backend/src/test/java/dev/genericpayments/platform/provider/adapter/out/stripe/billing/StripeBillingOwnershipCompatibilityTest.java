package dev.genericpayments.platform.provider.adapter.out.stripe.billing;
import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.catalog.domain.*;import dev.genericpayments.platform.customers.domain.CustomerId;import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.paymentaccounts.domain.*;import dev.genericpayments.platform.subscriptions.domain.*;import dev.genericpayments.platform.provider.application.model.ProviderOperation;import dev.genericpayments.platform.provider.application.port.out.StripeBillingBindingPort.CustomerBinding;
import java.time.Instant;import java.util.*;import org.junit.jupiter.api.Test;
class StripeBillingOwnershipCompatibilityTest {
 static UUID id(int n){return UUID.fromString("00000000-0000-0000-0000-"+String.format("%012d",n));}
 @Test void readsThePreTaskFiveStoredCreateFingerprintWithoutRekeying(){
  // Literal derived from committed 28ad7d4's persisted field order, not from the new helper.
  String saved="d1d35bcb857db4cd4201782bb5b4a520716027ea422253427845380ae3970f7f";
  var account=PaymentAccount.create(new PaymentAccountId(id(1)),"fixture",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_expected","stripe-sandbox-default");
  var terms=new SubscriptionPriceTerms(new PriceId(id(5)),"basic",1,new ProductId(id(4)),Money.of(1000,"EUR"),BillingInterval.MONTH,14,TrialPaymentMethodPolicy.OPTIONAL_DURING_TRIAL,TaxMode.NONE);
  var now=Instant.parse("2026-10-28T00:00:00Z");var s=new Subscription(id(6),new SiteId(id(2)),new CustomerId(id(3)),terms.productId(),account.id(),terms,null,Subscription.Status.INCOMPLETE,"sub_owned",null,null,null,null,null,null,null,1,now,now,null);
  var op=new SubscriptionOperation(id(7),s.siteId(),s.customerId(),s.id(),SubscriptionOperation.Kind.CREATE,SubscriptionOperation.Status.PROCESSING,"customer:fixture","key","v1.paid."+"a".repeat(64),"subscription:"+id(1)+":"+id(7)+":CREATE",0,1,"sub_owned",null,now,now,9);
  var customer=new CustomerBinding(id(8),account.id(),s.siteId(),s.customerId(),"cus_owned",id(9),0);var price=new StripeBillingSnapshotMapper.PublishedPrice(terms,"price_owned","prod_owned");
  var step=new ProviderOperation(id(10),account.id(),account.externalAccountId(),account.credentialsRef(),ProviderOperation.Kind.SUBSCRIPTION_CREATE,op.id(),op.providerOperationKey(),saved,ProviderOperation.Status.ACKNOWLEDGED,"sub_owned",now,now.plusSeconds(82800),null,null,5);
  var helper=new StripeBillingOwnership(null,null,null,StripeBillingGrantTokens::hash,null,null,null,null,null,null,null);
  assertThat(helper.fingerprint(account,s,op,customer,price)).isEqualTo(saved);
  assertThat(helper.originalPrice(account,s,op,customer,List.of(price),step)).isEqualTo(price);
  assertThatCode(()->StripeBillingOwnership.requireStep(account,s,op,step,saved)).doesNotThrowAnyException();
 }
}
