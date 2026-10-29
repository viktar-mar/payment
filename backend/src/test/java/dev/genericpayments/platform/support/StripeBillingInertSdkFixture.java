package dev.genericpayments.platform.support;

import com.stripe.StripeClient;
import com.stripe.exception.*;
import com.stripe.net.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.*;
import dev.genericpayments.platform.provider.application.model.*;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Inert official SDK transport and handwritten stores. No sockets, mocks, extensions or parent setup. */
public final class StripeBillingInertSdkFixture {
    public final PaymentAccount account=PaymentAccount.create(new PaymentAccountId(UUID.fromString("10000000-0000-0000-0000-000000000001")),"billing",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_expected","stripe-sandbox-default");
    public final SiteId site=new SiteId(UUID.fromString("20000000-0000-0000-0000-000000000002"));
    public final CustomerId customer=new CustomerId(UUID.fromString("30000000-0000-0000-0000-000000000003"));
    public final DigestPort digest=value->{try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new AssertionError(e);}};
    public final StripeCredentialResolver credentials=new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_INERT","pk_test_INERT","whsec_INERT"));
    public final Transport http=new Transport();
    // Deliberately build anew without super.builder: production transport must never be instantiated here.
    public final StripeClientFactory clients=new StripeClientFactory(){@Override public StripeClient create(StripeCredentials ignored){return StripeClient.builder().setApiKey("sk_test_INERT").setApiBase("https://api.stripe.com").setMaxNetworkRetries(0).setHttpClient(http).build();}};
    public int verifications,binds;public boolean bindingFailure,ackFailure,active=true;
    public final PaymentAccountVerificationPort verification=a->{verifications++;if(!a.equals(account))throw new IllegalStateException("foreign");};
    public final LoadPaymentAccountPort accounts=port(LoadPaymentAccountPort.class,m->Optional.of(account));
    public final LoadSitePort sites=port(LoadSitePort.class,m->Optional.of(Site.create(site,new SiteKey("billing-site"),"Billing",account.id(),Set.of(URI.create("https://shop.test")),Set.of(AuthenticationMode.SITE_TOKEN),PaymentMethodPolicy.automatic())));
    public final CustomerStore customers=port(CustomerStore.class,m->Optional.of(Customer.create(customer,site,null,null,Instant.now())));
    public final Map<UUID,ProviderOperation> rows=new LinkedHashMap<>();
    public final StripeBillingBindingPort bindings=port(StripeBillingBindingPort.class,m->{
        if(m.name().equals("bindCustomer")){binds++;if(bindingFailure){bindingFailure=false;throw new IllegalStateException("binding rollback");}return new StripeBillingBindingPort.CustomerBinding(UUID.fromString("60000000-0000-0000-0000-000000000006"),account.id(),site,customer,(String)m.args()[3],(UUID)m.args()[4],0);}
        return Optional.empty();
    });
    public ClockOwner owner;
    public ReadyClock ready;
    public StripeBillingClockStore.StopReason stopped;
    public int ownedReserves,claims,readyReads;
    public final StripeBillingClockStore clocks=new StripeBillingClockStore(){
        public ClockOwner admitFresh(PaymentAccount a,SiteId s,CustomerId c,UUID run,String api,long frozen,Instant now){if(owner!=null)throw new IllegalStateException();return admit(run,api,frozen,now);}
        public Optional<ClockOwner> owner(PaymentAccountId a,SiteId s,CustomerId c){return Optional.ofNullable(owner).filter(o->o.account().equals(a)&&o.site().equals(s)&&o.customer().equals(c));}
        public void requireActive(ClockOwner o){if(!active||stopped!=null||!o.equals(owner))throw new IllegalStateException("stopped");}
        public ReadyClock recordReady(ClockOwner o,ProviderOperation op,ReadyObservation r){requireActive(o);ready=new ReadyClock(o,op.id(),r.reference(),r.frozenTime(),r.providerCreated(),r.providerDeletesAfter(),r.observedAt(),StripeBillingClockIdentity.customerFingerprint(digest,o,op.id(),r.reference()));return ready;}
        public Optional<ReadyClock> ready(UUID intent){readyReads++;return Optional.ofNullable(ready).filter(r->r.owner().intent().equals(intent));}
        public void stop(ClockOwner o,StopReason r,Instant at){if(stopped==null)stopped=r;}
    };
    public final ProviderOperationStore operations=port(ProviderOperationStore.class,this::operation);
    public StripeBillingCustomerAdapter customerAdapter(){return new StripeBillingCustomerAdapter(credentials,clients,operations,bindings,digest,verification,accounts,sites,customers,clocks);}
    public StripeBillingTestClockAdapter clockAdapter(){return new StripeBillingTestClockAdapter(credentials,clients,verification,operations,clocks,digest);}
    public ClockOwner admit(){return admit(UUID.fromString("40000000-0000-0000-0000-000000000004"),StripeBillingTestClockAdapter.API_VERSION,1700000000,Instant.parse("2026-10-29T12:00:00Z"));}
    private ClockOwner admit(UUID run,String api,long frozen,Instant now){var intent=UUID.fromString("50000000-0000-0000-0000-000000000005");owner=new ClockOwner(intent,run,account.id(),site,customer,account.externalAccountId(),account.credentialsRef(),api,frozen,StripeBillingClockIdentity.name(intent),StripeBillingClockIdentity.createKey(account.id(),intent),StripeBillingClockIdentity.createFingerprint(digest,account,intent,run,site.value(),customer.value(),api,frozen),now);return owner;}
    public String clockJson(){return "{\"id\":\"clock_owned\",\"object\":\"test_helpers.test_clock\",\"livemode\":false,\"name\":\""+owner.name()+"\",\"frozen_time\":"+owner.initialFrozenTime()+",\"created\":1800000000,\"deletes_after\":1801000000,\"status\":\"ready\",\"status_details\":{}}";}
    public String customerJson(String clock){return "{\"id\":\"cus_owned\",\"object\":\"customer\",\"livemode\":false,\"created\":1600000000,\"test_clock\":"+(clock==null?"null":"\""+clock+"\"")+",\"metadata\":{\"account_id\":\""+account.id().value()+"\",\"site_id\":\""+site.value()+"\",\"customer_id\":\""+customer.value()+"\"}}";}
    public ReadyClock createReady(){admit();http.reply(clockJson());http.reply(clockJson());return clockAdapter().createFresh(account,owner);}
    private synchronized Object operation(Call m){var a=m.args();return switch(m.name()){
        case "reserve"->reserve((ProviderOperation.Kind)a[1],(UUID)a[2],(String)a[3],(String)a[4]);
        case "reserveOwnedBillingCustomer"->{ownedReserves++;clocks.requireActive(owner);yield reserve(ProviderOperation.Kind.CUSTOMER_CREATE,customer.value(),owner.customerKey(),ready.customerFingerprint());}
        case "find"->rows.values().stream().filter(o->o.operationKey().equals(a[1])).findFirst();
        case "findById"->Optional.ofNullable(rows.get(a[0]));
        case "claim"->{claims++;var old=rows.get(a[0]);if(old.firstDispatchedAt()!=null&&owner!=null||old.status()==ProviderOperation.Status.ACKNOWLEDGED)yield Optional.empty();var now=(Instant)a[2];var op=copy(old,ProviderOperation.Status.DISPATCHING,null,old.firstDispatchedAt()==null?now:old.firstDispatchedAt(),old.retryBefore()==null?now.plusSeconds(82800):old.retryBefore(),(String)a[1],now.plusSeconds(60),old.version()+1);rows.put(op.id(),op);yield Optional.of(op);}
        case "acknowledge"->{if(ackFailure)throw new IllegalStateException("late ACK");var old=rows.get(a[0]);var op=copy(old,ProviderOperation.Status.ACKNOWLEDGED,(String)a[3],old.firstDispatchedAt(),old.retryBefore(),null,null,old.version()+1);rows.put(op.id(),op);yield op;}
        case "unknown"->{var old=rows.get(a[0]);var op=copy(old,ProviderOperation.Status.UNKNOWN,null,old.firstDispatchedAt(),old.retryBefore(),null,null,old.version()+1);rows.put(op.id(),op);yield op;}
        default->throw new AssertionError("Unexpected operation: "+m.name());};}
    private ProviderOperation reserve(ProviderOperation.Kind kind,UUID resource,String key,String fp){return rows.values().stream().filter(o->o.operationKey().equals(key)).findFirst().orElseGet(()->{var op=new ProviderOperation(UUID.randomUUID(),account.id(),account.externalAccountId(),account.credentialsRef(),kind,resource,key,fp,ProviderOperation.Status.RESERVED,null,null,null,null,null,0);rows.put(op.id(),op);return op;});}
    public static ProviderOperation copy(ProviderOperation o,ProviderOperation.Status status,String ref,Instant first,Instant retry,String lease,Instant expires,long version){return new ProviderOperation(o.id(),o.accountId(),o.externalAccountId(),o.credentialsRef(),o.kind(),o.resourceId(),o.operationKey(),o.fingerprint(),status,ref,first,retry,lease,expires,version);}
    public record Call(String name,Object[] args){}
    @SuppressWarnings("unchecked") public static <T>T port(Class<T> type,Function<Call,Object> fn){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->fn.apply(new Call(m.getName(),a==null?new Object[0]:a)));}
    public static final class Transport extends HttpClient {
        public final List<StripeRequest> calls=new ArrayList<>();public final Deque<Object> replies=new ArrayDeque<>();public Runnable afterResponse;
        public void reply(String json){replies.add(new StripeResponse(200,HttpHeaders.of(Map.of()),json));}
        public void error(int code){replies.add(new StripeResponse(code,HttpHeaders.of(Map.of()),"{\"error\":{\"type\":\"api_error\",\"message\":\"synthetic\"}}"));}
        public void disconnect(){replies.add(new ApiConnectionException("inert disconnect"));}
        @Override public synchronized StripeResponse request(StripeRequest request)throws StripeException{calls.add(request);if(replies.isEmpty())throw new AssertionError("Unplanned inert SDK request");var response=replies.remove();if(response instanceof StripeException error)throw error;if(afterResponse!=null){var effect=afterResponse;afterResponse=null;effect.run();}return (StripeResponse)response;}
    }
}
