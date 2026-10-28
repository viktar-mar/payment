package dev.genericpayments.platform.provider.adapter.out.stripe;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.genericpayments.platform.customers.application.port.out.CustomerStore;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.*;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingCustomerAdapter;
import dev.genericpayments.platform.provider.application.model.ProviderOperation;
import dev.genericpayments.platform.provider.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import dev.genericpayments.platform.support.StripeHttpFixture;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StripeBillingCustomerAdapterTest {
    final PaymentAccount account = PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()), "billing",
        PaymentProviderType.STRIPE, PaymentAccountMode.SANDBOX, "acct_expected", "stripe-sandbox-default");
    final SiteId site = new SiteId(UUID.randomUUID());
    final CustomerId customer = new CustomerId(UUID.randomUUID());
    final CustomerStore customers = mock(CustomerStore.class);
    final LoadSitePort sites = mock(LoadSitePort.class);
    final LoadPaymentAccountPort accounts = mock(LoadPaymentAccountPort.class);
    final MemoryOperations operations = new MemoryOperations();
    final MemoryBindings bindings = new MemoryBindings(operations);
    StripeHttpFixture http;
    StripeBillingCustomerAdapter adapter;
    @BeforeEach void setup() throws Exception {
        http = new StripeHttpFixture();
        when(accounts.findById(account.id())).thenReturn(Optional.of(account));
        when(sites.findById(site)).thenReturn(Optional.of(Site.create(site, new SiteKey("billing-site"), "Billing",
            account.id(), Set.of(URI.create("https://shop.test")), Set.of(AuthenticationMode.SITE_TOKEN), PaymentMethodPolicy.automatic())));
        when(customers.findById(site, customer)).thenReturn(Optional.of(Customer.create(customer, site, null, null, Instant.now())));
        DigestPort digest = value -> {
            try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        };
        var credentials = new StripeCredentialResolver(new StripeSandboxProperties(true,
            "sk_test_" + "A".repeat(32), "pk_test_" + "B".repeat(32), "whsec_" + "C".repeat(32)));
        var factory = StripeClientFactoryTest.fixtureFactory(http.baseUri());
        adapter = new StripeBillingCustomerAdapter(credentials, factory, operations, bindings, digest,
            new StripeAccountVerificationAdapter(credentials, factory), accounts, sites, customers);
    }
    @AfterEach void close() { http.close(); }
    // Catches removing the transactional boundary that keeps provider HTTP outside caller transactions.
    @Test void callerTransactionIsRejectedBeforeProviderHttp() {
        var manager = new StripeAccountVerificationAdapterTest.TestTransactionManager();
        var factory = new org.springframework.aop.framework.ProxyFactory(adapter);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var proxied = (StripeBillingCustomerAdapter) factory.getProxy();
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status ->
            assertThatThrownBy(() -> proxied.ensureCustomer(account, site, customer))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class));
        assertThat(http.calls()).isEmpty(); assertThat(operations.rows).isEmpty();
    }
    // Catches dispatch before exact actor/account ownership is loaded; email lookup must never be used.
    @Test void foreignSiteAndCustomerNeverDispatch() {
        refused(() -> adapter.ensureCustomer(account, new SiteId(UUID.randomUUID()), customer), "PROVIDER_OPERATION_CONFLICT");
        refused(() -> adapter.ensureCustomer(account, site, new CustomerId(UUID.randomUUID())), "PROVIDER_OPERATION_CONFLICT");
        assertThat(http.calls()).isEmpty();
    }
    // Catches treating a non-unique profile email as provider Customer identity across Sites.
    @Test void sameEmailCustomersInDifferentSitesGetDistinctOwnedCustomers() {
        var secondSite = new SiteId(UUID.randomUUID()); var secondCustomer = new CustomerId(UUID.randomUUID());
        when(customers.findById(site, customer)).thenReturn(Optional.of(Customer.create(customer, site, "email-ref-one", "v1:same_email", Instant.now())));
        when(customers.findById(secondSite, secondCustomer)).thenReturn(Optional.of(Customer.create(secondCustomer, secondSite, "email-ref-two", "v1:same_email", Instant.now())));
        when(sites.findById(secondSite)).thenReturn(Optional.of(Site.create(secondSite, new SiteKey("second-site"), "Second",
            account.id(), Set.of(URI.create("https://other.test")), Set.of(AuthenticationMode.SITE_TOKEN), PaymentMethodPolicy.automatic())));
        identity(); http.enqueue("POST", "/v1/customers", 200, remote("cus_first"));
        identity(); http.enqueue("POST", "/v1/customers", 200, remote("cus_second").replace(site.value().toString(), secondSite.value().toString()).replace(customer.value().toString(), secondCustomer.value().toString()));
        assertThat(adapter.ensureCustomer(account, site, customer).providerReference()).isEqualTo("cus_first");
        assertThat(adapter.ensureCustomer(account, secondSite, secondCustomer).providerReference()).isEqualTo("cus_second");
        assertThat(posts()).hasSize(2).allSatisfy(p -> assertThat(p.form()).doesNotContainKey("email"));
        assertThat(bindings.rows).hasSize(2); assertThat(operations.rows).hasSize(2);
    }
    // Catches random resource identities and duplicate writes across concurrent subscriptions.
    @Test void concurrentSubscriptionsShareOneOriginalCustomerKey() throws Exception {
        identity(); identity();
        http.enqueueDelayedBody("POST", "/v1/customers", 200, remote("cus_owned"), 150);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<String> call = () -> { barrier.await(5, TimeUnit.SECONDS); try {
                return adapter.ensureCustomer(account, site, customer).providerReference();
            } catch (PaymentApplicationException safe) { return safe.code(); } };
            var first = pool.submit(call); var second = pool.submit(call);
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                .contains("cus_owned").allMatch(v -> v.equals("cus_owned") || v.equals("PROVIDER_RESULT_UNKNOWN"));
        }
        assertThat(posts()).hasSize(1);
        assertThat(posts().getFirst().header("Idempotency-Key"))
            .isEqualTo("billing_customer_" + account.id().value() + "_" + site.value() + "_" + customer.value());
        assertThat(posts().getFirst().form()).containsEntry("metadata[customer_id]", customer.value().toString())
            .containsEntry("metadata[site_id]", site.value().toString()).doesNotContainKeys("email", "name");
        assertThat(operations.rows).hasSize(1);
        assertThat(operations.only().resourceId()).isEqualTo(customer.value());
    }
    // Catches recreation after durable ACK but failed local mapping transaction.
    @Test void lostBindingCommitUsesAcknowledgedOriginalWithoutAnotherCreate() {
        identity(); http.enqueue("POST", "/v1/customers", 200, remote("cus_owned")); bindings.failNext = true;
        assertThatThrownBy(() -> adapter.ensureCustomer(account, site, customer)).isInstanceOf(IllegalStateException.class);
        identity(); assertThat(adapter.ensureCustomer(account, site, customer).providerReference()).isEqualTo("cus_owned");
        assertThat(posts()).hasSize(1); assertThat(bindings.rows).hasSize(1);
    }
    // Catches SDK automatic retries and a fresh key after uncertainty or at the 23h boundary.
    @Test void disconnectedCreateCannotWriteAfterWindow() {
        makeUnknown(); var original = operations.only(); operations.expire();
        identity(); refused(() -> adapter.ensureCustomer(account, site, customer), "PROVIDER_RESULT_UNKNOWN");
        assertThat(posts()).hasSize(1); assertThat(operations.only().operationKey()).isEqualTo(original.operationKey());
        assertThat(bindings.rows).isEmpty();
    }
    // Catches changing either original key or dispatch deadline during a guarded in-window replay.
    @Test void guardedUnknownReplayUsesOriginalKeyAndDeadline() {
        makeUnknown(); var original = operations.only(); identity(); http.enqueue("POST", "/v1/customers", 200, remote("cus_owned"));
        assertThat(adapter.ensureCustomer(account, site, customer).providerReference()).isEqualTo("cus_owned");
        assertThat(posts()).hasSize(2).allSatisfy(p -> assertThat(p.header("Idempotency-Key")).isEqualTo(original.operationKey()));
        assertThat(operations.only().firstDispatchedAt()).isEqualTo(original.firstDispatchedAt());
        assertThat(operations.only().retryBefore()).isEqualTo(original.retryBefore());
    }
    // Catches an old lease holder committing a Customer binding after another worker claims.
    @Test void staleDispatchCannotCommitBinding() {
        identity(); http.enqueue("POST", "/v1/customers", 200, remote("cus_owned")); operations.stealBeforeAck = true;
        refused(() -> adapter.ensureCustomer(account, site, customer), "PROVIDER_OPERATION_CONFLICT");
        assertThat(bindings.rows).isEmpty(); assertThat(posts()).hasSize(1);
    }
    // Catches ownership changes during network I/O being acknowledged after dispatch.
    @Test void changedCustomerAfterHttpCannotAcknowledgeOrBind() {
        var active = Customer.create(customer, site, null, null, Instant.now());
        when(customers.findById(site, customer)).thenReturn(Optional.of(active), Optional.of(active), Optional.of(active.anonymize(Instant.now())));
        identity(); http.enqueue("POST", "/v1/customers", 200, remote("cus_owned"));
        refused(() -> adapter.ensureCustomer(account, site, customer), "PROVIDER_OPERATION_CONFLICT");
        assertThat(bindings.rows).isEmpty(); assertThat(operations.only().status()).isNotEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
    }
    // Catches accepting local Account identity drift while the provider write is in flight.
    @Test void changedAccountAfterHttpCannotAcknowledgeOrBind() {
        var changed = new PaymentAccount(account.id(), account.key(), account.provider(), account.mode(), account.status(), "acct_foreign", account.credentialsRef());
        when(accounts.findById(account.id())).thenReturn(Optional.of(account), Optional.of(account), Optional.of(changed));
        identity(); http.enqueue("POST", "/v1/customers", 200, remote("cus_owned"));
        refused(() -> adapter.ensureCustomer(account, site, customer), "PROVIDER_OPERATION_CONFLICT");
        assertThat(posts()).hasSize(1); assertThat(bindings.rows).isEmpty();
        assertThat(operations.only().status()).isNotEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
    }
    // Catches metadata-only recovery and accepting a different request's idempotency key.
    @Test void metadataCannotReplaceOriginalCreationRequestProof() {
        makeUnknown(); var original = operations.only(); identity(); event("evt_wrong", "wrong-key", "cus_owned", original.firstDispatchedAt().getEpochSecond());
        refused(() -> adapter.recoverCustomer(account, site, customer, original.id(), "evt_wrong"), "PROVIDER_EVIDENCE_MISMATCH");
        assertThat(bindings.rows).isEmpty(); assertThat(posts()).hasSize(1);
    }
    // Catches recovery without both original event proof and a fresh same-account Customer retrieve.
    @Test void expiredUnknownRecoversWithoutAnotherCreate() {
        makeUnknown(); operations.expire(); var original = operations.only(); identity();
        event("evt_owned", original.operationKey(), "cus_owned", original.firstDispatchedAt().getEpochSecond());
        http.enqueue("GET", "/v1/customers/cus_owned", 200, remote("cus_owned", original.firstDispatchedAt().getEpochSecond()));
        assertThat(adapter.recoverCustomer(account, site, customer, original.id(), "evt_owned").providerReference()).isEqualTo("cus_owned");
        assertThat(posts()).hasSize(1); assertThat(operations.only().status()).isEqualTo(ProviderOperation.Status.ACKNOWLEDGED);
    }
    // Catches provider parser/shape failures leaking an unsafe runtime exception.
    @Test void malformedCreationEventReturnsFixedSafeFailure() {
        makeUnknown(); var original = operations.only(); identity();
        http.enqueue("GET", "/v1/events/evt_owned", 200,
            eventJson("evt_owned", original.operationKey(), "cus_owned", original.firstDispatchedAt().getEpochSecond())
                .replace("\"data\":{\"object\":" + remote("cus_owned", original.firstDispatchedAt().getEpochSecond()) + "}", "\"data\":null"));
        refused(() -> adapter.recoverCustomer(account, site, customer, original.id(), "evt_owned"), "PROVIDER_EVIDENCE_MISMATCH");
        assertThat(bindings.rows).isEmpty();
    }
    // Catches accepting non-pinned, live, Connect, wrong-kind, or foreign-object creation evidence.
    @ParameterizedTest @ValueSource(strings = {"api", "live", "type", "account", "context", "metadata", "before", "after", "event-id"})
    void unownedCreationEvidenceNeverBinds(String fault) {
        makeUnknown(); var original = operations.only(); identity();
        String json = eventJson("evt_owned", original.operationKey(), "cus_owned", original.firstDispatchedAt().getEpochSecond());
        json = switch (fault) {
            case "api" -> json.replace("2026-09-30.endive", "2025-01-01.test");
            case "live" -> json.replace("\"livemode\":false", "\"livemode\":true");
            case "type" -> json.replace("customer.created", "customer.updated");
            case "account" -> json.replace("\"object\":\"event\"", "\"object\":\"event\",\"account\":\"acct_foreign\"");
            case "context" -> json.replace("\"object\":\"event\"", "\"object\":\"event\",\"context\":\"ctx_foreign\"");
            case "metadata" -> json.replace(customer.value().toString(), UUID.randomUUID().toString());
            case "before" -> eventJson("evt_owned", original.operationKey(), "cus_owned", original.firstDispatchedAt().getEpochSecond() - 1);
            case "after" -> eventJson("evt_owned", original.operationKey(), "cus_owned", original.retryBefore().getEpochSecond());
            default -> json.replace("evt_owned", "evt_foreign");
        };
        http.enqueue("GET", "/v1/events/evt_owned", 200, json);
        refused(() -> adapter.recoverCustomer(account, site, customer, original.id(), "evt_owned"), "PROVIDER_EVIDENCE_MISMATCH");
        assertThat(bindings.rows).isEmpty(); assertThat(posts()).hasSize(1);
    }
    // Catches bypassing an active worker's fence even when the original event is available.
    @Test void recoveryRefusesLiveCompetingLeaseBeforeHttp() {
        makeUnknown(); var original = operations.only();
        operations.claim(original.id(), "competitor", Instant.now(), Duration.ofSeconds(60)).orElseThrow();
        int before = http.calls().size();
        refused(() -> adapter.recoverCustomer(account, site, customer, original.id(), "evt_owned"), "PROVIDER_EVIDENCE_MISMATCH");
        assertThat(http.calls()).hasSize(before); assertThat(bindings.rows).isEmpty();
    }
    // Catches accepting a Customer retrieve different from the authoritative creation event.
    @Test void freshCustomerMustMatchCreationEvent() {
        makeUnknown(); var original = operations.only(); identity();
        event("evt_owned", original.operationKey(), "cus_owned", original.firstDispatchedAt().getEpochSecond());
        http.enqueue("GET", "/v1/customers/cus_owned", 200, remote("cus_foreign", original.firstDispatchedAt().getEpochSecond()));
        refused(() -> adapter.recoverCustomer(account, site, customer, original.id(), "evt_owned"), "PROVIDER_EVIDENCE_MISMATCH");
        assertThat(bindings.rows).isEmpty();
    }
    // Catches accepting a malformed creation/retrieve pair with no immutable creation time.
    @Test void recoveryRequiresCustomerCreationTime() {
        makeUnknown(); var original = operations.only(); identity();
        event("evt_owned", original.operationKey(), "cus_owned", original.firstDispatchedAt().getEpochSecond());
        http.enqueue("GET", "/v1/customers/cus_owned", 200, remote("cus_owned"));
        refused(() -> adapter.recoverCustomer(account, site, customer, original.id(), "evt_owned"), "PROVIDER_EVIDENCE_MISMATCH");
        assertThat(bindings.rows).isEmpty();
    }
    // Catches accepting a typed SDK response whose actual provider object kind is wrong.
    @Test void wrongCreateObjectKindStaysUnknown() {
        identity(); http.enqueue("POST", "/v1/customers", 200, remote("cus_owned").replace("\"object\":\"customer\"", "\"object\":\"invoice\""));
        refused(() -> adapter.ensureCustomer(account, site, customer), "PROVIDER_RESULT_UNKNOWN");
        assertThat(operations.only().status()).isEqualTo(ProviderOperation.Status.UNKNOWN); assertThat(bindings.rows).isEmpty();
    }
    void makeUnknown() { identity(); http.disconnectNext("POST", "/v1/customers"); refused(() -> adapter.ensureCustomer(account, site, customer), "PROVIDER_RESULT_UNKNOWN"); }
    void identity() { http.enqueue("GET", "/v1/account", 200, "{\"id\":\"acct_expected\",\"object\":\"account\"}"); }
    String remote(String id) { return "{\"id\":\"" + id + "\",\"object\":\"customer\",\"livemode\":false,\"metadata\":{\"customer_id\":\"" + customer.value() + "\",\"site_id\":\"" + site.value() + "\",\"account_id\":\"" + account.id().value() + "\"}}"; }
    String remote(String id, long created) { return remote(id).replace("\"object\":\"customer\"", "\"object\":\"customer\",\"created\":" + created); }
    void event(String id, String key, String ref, long created) {
        http.enqueue("GET", "/v1/events/" + id, 200, eventJson(id,key,ref,created));
    }
    String eventJson(String id, String key, String ref, long created) { return "{\"id\":\"" + id + "\",\"object\":\"event\",\"api_version\":\"2026-09-30.endive\",\"livemode\":false,\"type\":\"customer.created\",\"created\":" + created + ",\"request\":{\"id\":\"req_owned\",\"idempotency_key\":\"" + key + "\"},\"data\":{\"object\":" + remote(ref, created) + "}}"; }
    List<StripeHttpFixture.Call> posts() { return http.calls().stream().filter(c -> c.method().equals("POST") && c.path().equals("/v1/customers")).toList(); }
    static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(PaymentApplicationException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
    /** Test-only durable boundary substitute; real JDBC fencing is covered by PostgreSQL tests. */
    static class MemoryOperations implements ProviderOperationStore {
        final Map<UUID, ProviderOperation> rows = new HashMap<>(); boolean stealBeforeAck;
        synchronized ProviderOperation only() { return rows.values().iterator().next(); }
        public synchronized ProviderOperation reserve(PaymentAccount a, ProviderOperation.Kind kind, UUID resource, String key, String fp) {
            var found = rows.values().stream().filter(o -> o.accountId().equals(a.id()) && (o.operationKey().equals(key) || o.kind() == kind && o.resourceId().equals(resource))).findFirst();
            if (found.isPresent()) { var op = found.get(); if (!op.operationKey().equals(key) || !op.fingerprint().equals(fp)) throw conflict(); return op; }
            var op = new ProviderOperation(UUID.randomUUID(), a.id(), a.externalAccountId(), a.credentialsRef(), kind, resource, key, fp, ProviderOperation.Status.RESERVED, null, null, null, null, null, 0);
            rows.put(op.id(), op); return op;
        }
        public synchronized Optional<ProviderOperation> find(PaymentAccountId a, String key) { return rows.values().stream().filter(o -> o.accountId().equals(a) && o.operationKey().equals(key)).findFirst(); }
        public synchronized Optional<ProviderOperation> findById(UUID id) { return Optional.ofNullable(rows.get(id)); }
        public synchronized Optional<ProviderOperation> claim(UUID id, String owner, Instant now, Duration lease) {
            var o = rows.get(id); if (o.status() == ProviderOperation.Status.ACKNOWLEDGED || o.leaseExpiresAt() != null && now.isBefore(o.leaseExpiresAt()) || o.retryBefore() != null && !now.isBefore(o.retryBefore())) return Optional.empty();
            return Optional.of(replace(o, ProviderOperation.Status.DISPATCHING, null, o.firstDispatchedAt() == null ? now : o.firstDispatchedAt(), o.retryBefore() == null ? now.plus(Duration.ofHours(23)) : o.retryBefore(), owner, now.plus(lease)));
        }
        public synchronized ProviderOperation acknowledge(UUID id, String owner, long version, String ref) {
            var o = rows.get(id); if (stealBeforeAck) { stealBeforeAck = false; replace(o, o.status(), null, o.firstDispatchedAt(), o.retryBefore(), "another-owner", Instant.now().plusSeconds(60)); }
            o = rows.get(id); if (!Objects.equals(o.leaseOwner(), owner) || o.version() != version) throw conflict();
            return replace(o, ProviderOperation.Status.ACKNOWLEDGED, ref, o.firstDispatchedAt(), o.retryBefore(), null, null);
        }
        public synchronized ProviderOperation unknown(UUID id, String owner, long version) {
            var o = rows.get(id); if (!Objects.equals(o.leaseOwner(), owner) || o.version() != version) throw conflict();
            return replace(o, ProviderOperation.Status.UNKNOWN, null, o.firstDispatchedAt(), o.retryBefore(), null, null);
        }
        public synchronized ProviderOperation bindRecovered(ProviderOperation expected, String ref, String fp) {
            var o = rows.get(expected.id()); if (!o.equals(expected) || !o.fingerprint().equals(fp) || o.leaseExpiresAt() != null && Instant.now().isBefore(o.leaseExpiresAt())) throw conflict();
            return replace(o, ProviderOperation.Status.ACKNOWLEDGED, ref, o.firstDispatchedAt(), o.retryBefore(), null, null);
        }
        public ProviderOperation lockRecovery(ProviderOperation expected) { if (!rows.get(expected.id()).equals(expected)) throw conflict(); return expected; }
        public ProviderOperation bindRecoveredInTransaction(ProviderOperation expected, String ref, String fp) { return bindRecovered(expected, ref, fp); }
        synchronized void expire() { var o = only(); var first = Instant.now().minus(Duration.ofHours(23)); replace(o, o.status(), o.providerReference(), first, first.plus(Duration.ofHours(23)), null, null); }
        ProviderOperation replace(ProviderOperation o, ProviderOperation.Status state, String ref, Instant first, Instant until, String owner, Instant lease) {
            var result = new ProviderOperation(o.id(), o.accountId(), o.externalAccountId(), o.credentialsRef(), o.kind(), o.resourceId(), o.operationKey(), o.fingerprint(), state, ref, first, until, owner, lease, o.version() + 1); rows.put(o.id(), result); return result;
        }
        static PaymentApplicationException conflict() { return new PaymentApplicationException("PROVIDER_OPERATION_CONFLICT", 409, "Provider operation conflicts with the request"); }
    }
    static class MemoryBindings implements StripeBillingBindingPort {
        final MemoryOperations operations; final Map<String, CustomerBinding> rows = new HashMap<>(); boolean failNext;
        MemoryBindings(MemoryOperations operations) { this.operations = operations; }
        public synchronized Optional<CustomerBinding> findCustomer(PaymentAccountId a, SiteId s, CustomerId c) { return Optional.ofNullable(rows.get(a + ":" + s + ":" + c)); }
        public synchronized CustomerBinding bindCustomer(PaymentAccountId a, SiteId s, CustomerId c, String ref, UUID opId) {
            if (failNext) { failNext = false; throw new IllegalStateException("injected binding rollback"); }
            var op = operations.findById(opId).orElseThrow();
            if (op.status() != ProviderOperation.Status.ACKNOWLEDGED || !ref.equals(op.providerReference()) || !a.equals(op.accountId()) || !c.value().equals(op.resourceId())) throw MemoryOperations.conflict();
            return rows.computeIfAbsent(a + ":" + s + ":" + c, ignored -> new CustomerBinding(UUID.randomUUID(), a, s, c, ref, opId, 0));
        }
    }
}
