package dev.genericpayments.platform.provider.adapter.out.stripe.billing;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentMethod;
import com.stripe.model.SetupIntent;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import dev.genericpayments.platform.paymentaccounts.application.port.out.PaymentAccountVerificationPort;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccount;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountMode;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountStatus;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentProviderType;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeClientFactory;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeCredentialResolver;
import dev.genericpayments.platform.provider.adapter.out.stripe.StripeRequestFingerprint;
import dev.genericpayments.platform.provider.application.model.ProviderOperation.Kind;
import dev.genericpayments.platform.shared.application.port.out.DigestPort;
import dev.genericpayments.platform.subscriptions.adapter.out.persistence.SubscriptionBillingProofPersistenceAdapter;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionAuthorityPort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionBillingProofStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionOperationStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionStore;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionInteractionTransaction;
import dev.genericpayments.platform.subscriptions.domain.Subscription;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialCreate.*;
import static dev.genericpayments.platform.provider.adapter.out.stripe.billing.StripeBillingTestClockAdapter.options;

/** Explicit test fixture. Original SETUP writes have one durable claim each and use the normal billing proof and fact stores. */
public final class OwnedOptionalTrialSetup {
    private static final long BROWSER_LEASE_SECONDS = 180;

    static final class Prepared {
        private final OwnedOptionalTrialSetup producer;
        private final SubscriptionCommandContext context;
        private final Scope scope;
        private final UUID operation;
        private final String reference;
        private final long created;
        private final Instant preparedAt;
        private final long preparedNanos;
        // Guarded by this Prepared. It cannot be reconstructed from the durable SETUP receipt.
        private boolean browserAttempted;
        private boolean completionStarted;
        private Instant browserExpiry;

        private Prepared(OwnedOptionalTrialSetup producer, SubscriptionCommandContext context, Scope scope, UUID operation,
                SetupIntent intent) {
            this.producer = producer;
            this.context = context;
            this.scope = scope;
            this.operation = operation;
            reference = intent.getId();
            created = intent.getCreated();
            preparedAt = producer.operationalClock.instant();
            preparedNanos = System.nanoTime();
        }

        UUID operation() { return operation; }

        @Override public String toString() { return "OwnedOptionalTrialSetup.Prepared[redacted]"; }
    }

    public static final class SetupPermit {
        private final String reference;
        private final String publishableKey;
        private final String clientSecret;
        private final Instant expiresAt;

        private SetupPermit(String reference, String publishableKey, String clientSecret, Instant expiresAt) {
            this.reference = reference;
            this.publishableKey = publishableKey;
            this.clientSecret = clientSecret;
            this.expiresAt = expiresAt;
        }

        public String reference() { return reference; }
        public String publishableKey() { return publishableKey; }
        public String clientSecret() { return clientSecret; }
        public Instant expiresAt() { return expiresAt; }

        @Override public String toString() { return "OwnedOptionalTrialSetup.SetupPermit[redacted]"; }
    }

    private record Paused(Authority authority, Created original, long revision, long policyVersion) {}
    private record State(Paused paused, Subscription subscription, SubscriptionOperation operation) {}
    private record Claim(UUID step, UUID owner, String key) {}

    private final JdbcTemplate jdbc;
    private final SubscriptionTransactionPort transactions;
    private final SubscriptionAuthorityPort actors;
    private final SubscriptionOperationStore operations;
    private final SubscriptionStore subscriptions;
    private final DigestPort digest;
    private final StripeCredentialResolver credentials;
    private final StripeClientFactory clients;
    private final PaymentAccountVerificationPort verification;
    private final OwnedOptionalTrialPublication publication;
    private final Clock operationalClock;
    // Use the normal proof writer inside each caller-owned ACK transaction.
    private final SubscriptionBillingProofPersistenceAdapter proofs;

    OwnedOptionalTrialSetup(JdbcTemplate jdbc, SubscriptionTransactionPort transactions, SubscriptionAuthorityPort actors,
            SubscriptionOperationStore operations, SubscriptionStore subscriptions, DigestPort digest,
            StripeCredentialResolver credentials, StripeClientFactory clients, PaymentAccountVerificationPort verification,
            OwnedOptionalTrialPublication publication, Clock operationalClock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.actors = actors;
        this.operations = operations;
        this.subscriptions = subscriptions;
        this.digest = digest;
        this.credentials = credentials;
        this.clients = clients;
        this.verification = verification;
        this.publication = publication;
        this.operationalClock = operationalClock;
        proofs = new SubscriptionBillingProofPersistenceAdapter(jdbc);
    }

    Prepared prepare(PaymentAccount account, Scope scope, SubscriptionCommandContext context, boolean recurringConsent) {
        require(!transactions.active() && recurringConsent && context != null && context.idempotencyKey() != null
            && !context.idempotencyKey().isBlank() && context.idempotencyKey().length() <= 255
            && context.idempotencyKey().codePoints().noneMatch(Character::isISOControl));
        Claim claim = null;
        try {
            var paused = transactions.requiresNew(() -> {
                var state = locked(account, scope, context, null);
                requireFresh(state.paused());
                return state.paused();
            });
            verification.verify(account);
            var client = clients.create(credentials.resolve(account));
            rereadPaused(client, paused.authority(), paused.original(), paused.authority().trialEnd());

            var operationId = UUID.randomUUID();
            claim = transactions.requiresNew(() -> {
                var now = operationalClock.instant();
                var operation = new SubscriptionOperation(operationId, context.siteId(), context.customerId(), scope.subscription(),
                    SubscriptionOperation.Kind.SETUP, SubscriptionOperation.Status.RESERVED,
                    "customer:" + context.customerId().value(), digest.sha256Hex(context.idempotencyKey()),
                    SubscriptionInteractionTransaction.request(context, "SETUP", scope.subscription(), true),
                    "subscription:" + account.id().value() + ":" + operationId + ":SETUP",
                    paused.revision(), paused.policyVersion(), paused.original().subscription(), null, now, now, 0);
                var owner = operations.claim(operation);
                require(owner.owner() && owner.operation().id().equals(operationId));
                // Match the operation-first order used by ordinary subscription interactions.
                var state = locked(account, scope, context, operationId);
                require(paused.equals(state.paused()) && owner.operation().equals(state.operation()));
                requireUnclaimed(paused);
                proofs.authorizeSetup(state.subscription(), state.operation());
                String key = operation.providerOperationKey() + ":SETUP";
                String fingerprint = StripeRequestFingerprint.of(digest, account, "SETUP_INTENT_CREATE_V1",
                    operationId.toString(), key, operation.fingerprint(), Long.toString(operation.policyVersion()),
                    scope.subscription().toString(), paused.authority().site().toString(), paused.authority().customer().toString(),
                    paused.authority().customerReference(), "off_session", "card", "consent:true");
                return claim(paused, owner.operation(), Kind.SETUP_INTENT_CREATE, key, fingerprint, null);
            });
            requireReadyDispatch(client, paused.authority(), claim);
            var intent = createIntent(client, paused.authority(), operationId, claim.key());
            verifyReady(client, paused.authority(), paused.authority().trialEnd());
            var dispatched = claim;
            transactions.requiresNew(() -> {
                var state = locked(account, scope, context, operationId);
                require(paused.equals(state.paused()) && state.operation().status() == SubscriptionOperation.Status.RESERVED
                    && state.operation().version() == 0);
                finish(dispatched, intent.getId());
                var proof = proofs.recordSetup(state.subscription(), state.operation(), dispatched.step(), intent.getId());
                require(proof.method() == null);
                operations.save(processing(state.operation()), state.operation().version());
                return null;
            });
            return new Prepared(this, context, scope, operationId, intent);
        } catch (StripeException | RuntimeException failure) {
            markUnknown(claim);
            throw unavailable();
        }
    }

    SetupPermit claimBrowserPermit(PaymentAccount account, Scope scope, SubscriptionCommandContext context, Prepared prepared) {
        requirePrepared(scope, context, prepared);
        require(!TransactionSynchronizationManager.hasResource(jdbc.getDataSource()));
        synchronized (prepared) {
            require(!prepared.browserAttempted && !prepared.completionStarted);
            prepared.browserAttempted = true;
        }
        try {
            var session = ((SubscriptionActor.Customer) context.actor()).session();
            var expiresAt = earliest(prepared.preparedAt.plusSeconds(BROWSER_LEASE_SECONDS), session.expiresAt());
            requireBrowserLease(prepared, expiresAt);
            var initial = pendingState(account, scope, context, prepared);
            var a = initial.paused().authority();
            expiresAt = earliest(expiresAt, Instant.ofEpochSecond(a.clockDeletesAfter()));
            requireBrowserLease(prepared, expiresAt);

            verification.verify(account);
            var credential = credentials.resolve(account);
            var publishableKey = credential.publishableKey();
            require(publishableKey != null && publishableKey.matches("pk_test_[A-Za-z0-9]+"));
            var client = clients.create(credential);
            rereadPaused(client, a, initial.paused().original(), a.trialEnd());
            var remote = client.v1().setupIntents().retrieve(prepared.reference, options(null));
            requireIntent(remote, a, prepared.operation);
            require(prepared.reference.equals(remote.getId()) && Objects.equals(prepared.created, remote.getCreated())
                && "requires_payment_method".equals(remote.getStatus()) && remote.getPaymentMethod() == null
                && remote.getLastSetupError() == null && remote.getNextAction() == null);
            String secret = remote.getClientSecret();
            require(secret != null && secret.length() <= 512
                && secret.matches(Pattern.quote(prepared.reference) + "_secret_[A-Za-z0-9_]+"));
            verifyReady(client, a, a.trialEnd());
            require(initial.equals(pendingState(account, scope, context, prepared)));
            requireBrowserLease(prepared, expiresAt);

            synchronized (prepared) {
                require(!prepared.completionStarted);
                prepared.browserExpiry = expiresAt;
            }
            return new SetupPermit(prepared.reference, publishableKey, secret, expiresAt);
        } catch (StripeException | RuntimeException failure) {
            throw unavailable();
        }
    }

    void complete(PaymentAccount account, Scope scope, SubscriptionCommandContext context, Prepared prepared) {
        requirePrepared(scope, context, prepared);
        synchronized (prepared) {
            requireCurrentBrowserPermit(prepared);
            prepared.completionStarted = true;
        }
        Claim claim = null;
        try {
            var initial = pendingState(account, scope, context, prepared);
            var paused = initial.paused();
            var a = paused.authority();
            verification.verify(account);
            var client = clients.create(credentials.resolve(account));
            String method = confirmedCard(client, a, prepared.operation, prepared.reference, prepared.created);
            rereadPaused(client, a, paused.original(), a.trialEnd(), method, null);
            requireCurrentBrowserPermit(prepared);

            claim = transactions.requiresNew(() -> {
                var state = locked(account, scope, context, prepared.operation);
                require(initial.equals(state));
                var proof = originalProof(state, prepared);
                requireNoMethodClaim(scope);
                String key = state.operation().providerOperationKey() + ":METHOD";
                String fingerprint = StripeRequestFingerprint.of(digest, account, "SUBSCRIPTION_METHOD_V1",
                    prepared.operation.toString(), key, state.operation().fingerprint(), paused.original().subscription(),
                    a.customerReference(), proof.step().toString(), proof.reference(), method, "consent:true");
                return claim(paused, state.operation(), Kind.SUBSCRIPTION_UPDATE, key, fingerprint, method);
            });
            requireReadyDispatch(client, a, claim);
            requireCurrentBrowserPermit(prepared);
            installDefault(client, a, paused.original(), method, claim.key());
            rereadPaused(client, a, paused.original(), a.trialEnd(), method, method);
            var dispatched = claim;
            transactions.requiresNew(() -> {
                var state = locked(account, scope, context, prepared.operation);
                require(initial.equals(state));
                var proof = originalProof(state, prepared);
                finish(dispatched, paused.original().subscription());
                proofs.bindMethod(proof, method);
                return null;
            });
            publication.publishSetup(account, scope, prepared.operation);
        } catch (StripeException | RuntimeException failure) {
            markUnknown(claim);
            throw unavailable();
        }
    }

    private void requirePrepared(Scope scope, SubscriptionCommandContext context, Prepared prepared) {
        require(!transactions.active() && prepared != null && prepared.producer == this
            && prepared.scope.equals(scope) && prepared.context.equals(context));
    }

    private State pendingState(PaymentAccount account, Scope scope, SubscriptionCommandContext context, Prepared prepared) {
        return transactions.requiresNew(() -> {
            var state = locked(account, scope, context, prepared.operation);
            originalProof(state, prepared);
            requireNoMethodClaim(scope);
            return state;
        });
    }

    private void requireCurrentBrowserPermit(Prepared prepared) {
        synchronized (prepared) {
            if (prepared.browserAttempted) {
                require(prepared.browserExpiry != null);
                requireBrowserLease(prepared, prepared.browserExpiry);
            }
        }
    }

    private void requireBrowserLease(Prepared prepared, Instant expiresAt) {
        long elapsed = System.nanoTime() - prepared.preparedNanos;
        require(elapsed >= 0 && elapsed < TimeUnit.SECONDS.toNanos(BROWSER_LEASE_SECONDS)
            && !operationalClock.instant().isBefore(prepared.preparedAt) && operationalClock.instant().isBefore(expiresAt));
    }

    private static Instant earliest(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private State locked(PaymentAccount account, Scope scope, SubscriptionCommandContext context, UUID operationId) {
        require(context != null && context.actor() instanceof SubscriptionActor.Customer);
        var operation = operationId == null ? null : operations.lock(operationId);
        actors.revalidate(context, true);
        require(jdbc.queryForList("select id from payment_accounts where id=? for update", UUID.class,
            account.id().value()).size() == 1);
        var subscription = subscriptions.lock(context.siteId(), scope.subscription());
        require(jdbc.queryForList("select id from stripe_billing_clock_intents where id=? and run_id=? and stopped_at is null for update",
            UUID.class, scope.clockIntent(), scope.run()).size() == 1);
        var rows = jdbc.query("""
            select * from stripe_billing_optional_trial_setup_contexts
            where run_id=? and clock_intent=? and subscription_id=? and create_operation=? and account_id=? and site_id=? and customer_id=?
            """, (r,n) -> new Paused(JdbcOwnedOptionalTrialCreateStore.authority(r),
                new Created(r.getString("subscription_reference"), r.getString("item_reference"),
                    r.getString("invoice_reference"), r.getLong("trial_start")),
                r.getLong("revision"), r.getLong("policy_version")),
            scope.run(), scope.clockIntent(), scope.subscription(), scope.createOperation(),
            account.id().value(), context.siteId().value(), context.customerId().value());
        require(rows.size() == 1);
        var paused = rows.getFirst();
        var a = paused.authority();
        require(account.status() == PaymentAccountStatus.ACTIVE && account.provider() == PaymentProviderType.STRIPE
            && account.mode() == PaymentAccountMode.SANDBOX && "stripe-sandbox-default".equals(account.key())
            && a.externalAccount().equals(account.externalAccountId()) && a.credentialsRef().equals(account.credentialsRef()));
        if (operation != null) {
            require(operation.siteId().equals(context.siteId()) && operation.customerId().equals(context.customerId())
                && operation.subscriptionId().equals(scope.subscription()) && operation.kind() == SubscriptionOperation.Kind.SETUP
                && operation.expectedRevision() == paused.revision() && operation.policyVersion() == paused.policyVersion()
                && paused.original().subscription().equals(operation.providerReference())
                && operation.fingerprint().equals(SubscriptionInteractionTransaction.request(context, "SETUP", scope.subscription(), true))
                && !exists("select 1 from subscription_operations where subscription_id=? and id not in (?,?)",
                    scope.subscription(), scope.createOperation(), operation.id()));
        }
        return new State(paused, subscription, operation);
    }

    private void requireFresh(Paused paused) {
        var a = paused.authority();
        require(!exists("select 1 from subscription_operations where subscription_id=? and id<>?", a.subscription(), a.createOperation()));
        requireUnclaimed(paused);
    }

    private void requireUnclaimed(Paused paused) {
        var a = paused.authority();
        require(!exists("select 1 from stripe_subscription_setup_proofs where subscription_id=?", a.subscription())
            && !exists("select 1 from stripe_billing_optional_trial_setup_steps where clock_intent=?", a.clockIntent()));
    }

    private void requireNoMethodClaim(Scope scope) {
        require(!exists("""
            select 1 from stripe_billing_optional_trial_setup_steps where clock_intent=? and step_kind='SUBSCRIPTION_UPDATE'
            """, scope.clockIntent()));
    }

    private SubscriptionBillingProofStore.Setup originalProof(State state, Prepared prepared) {
        var operation = state.operation();
        var a = state.paused().authority();
        require(operation.status() == SubscriptionOperation.Status.PROCESSING && operation.version() == 1);
        var proof = proofs.setup(state.subscription().paymentAccountId(), operation.id()).orElseThrow(OwnedOptionalTrialSetup::unavailable);
        require(proof.subscription().equals(a.subscription()) && proof.method() == null && prepared.reference.equals(proof.reference()));
        require(exists("""
            select 1 from stripe_billing_optional_trial_setup_steps claim join provider_operations step on step.id=claim.step_id
            where claim.clock_intent=? and claim.setup_operation=? and claim.step_id=? and claim.step_kind='SETUP_INTENT_CREATE'
              and step.payment_account_id=? and step.resource_id=claim.setup_operation and step.kind=claim.step_kind
              and step.status='ACKNOWLEDGED' and step.version=2 and step.provider_reference=?
              and step.operation_key=? and step.external_account_id=? and step.credentials_ref=?
            """, a.clockIntent(), operation.id(), proof.step(), a.account(), proof.reference(),
            operation.providerOperationKey() + ":SETUP", a.externalAccount(), a.credentialsRef()));
        return proof;
    }

    private Claim claim(Paused paused, SubscriptionOperation operation, Kind kind, String key, String fingerprint,
            String method) {
        var a = paused.authority();
        var claim = new Claim(UUID.randomUUID(), UUID.randomUUID(), key);
        require(jdbc.update("""
            with dispatch_time as (select clock_timestamp() as at)
            insert into provider_operations(id,payment_account_id,external_account_id,credentials_ref,kind,resource_id,operation_key,
               fingerprint,status,first_dispatched_at,retry_before,lease_owner,lease_expires_at,version,manual_review_required)
            select ?,?,?,?,?,?,?,?,'DISPATCHING',at,at+interval '23 hours',?,at+interval '60 seconds',1,true from dispatch_time
            """, claim.step(), a.account(), a.externalAccount(), a.credentialsRef(), kind.name(), operation.id(), key,
            fingerprint, claim.owner().toString()) == 1);
        require(jdbc.update("""
            insert into stripe_billing_optional_trial_setup_steps(clock_intent,step_kind,setup_operation,step_id,claim_id,method_reference)
            values(?,?,?,?,?,?)
            """, a.clockIntent(), kind.name(), operation.id(), claim.step(), claim.owner(), method) == 1);
        return claim;
    }

    private void requireReadyDispatch(StripeClient client, Authority a, Claim claim) throws StripeException {
        requireDispatch(claim);
        verifyReady(client, a, a.trialEnd());
        requireDispatch(claim);
    }

    private void requireDispatch(Claim claim) {
        final long started = System.nanoTime();
        var times = jdbc.queryForList("""
            select floor(extract(epoch from (step.lease_expires_at-clock_timestamp()))*1000)::bigint
            from stripe_billing_optional_trial_setup_steps claim
            join stripe_billing_optional_trial_setup_contexts a on a.clock_intent=claim.clock_intent
            join provider_operations step on step.id=claim.step_id and step.lease_owner=claim.claim_id::text
            join subscription_operations o on o.id=claim.setup_operation and o.expected_revision=a.revision
            where step.id=? and step.lease_owner=? and step.status='DISPATCHING' and step.version=1 and step.operation_key=?
              and ((step.kind='SETUP_INTENT_CREATE' and o.status='RESERVED' and o.version=0)
                or (step.kind='SUBSCRIPTION_UPDATE' and o.status='PROCESSING' and o.version=1))
              and not exists(select 1 from subscription_operations other where other.subscription_id=a.subscription_id
                and other.id not in (a.create_operation,o.id))
            """, Long.class, claim.step(), claim.owner().toString(), claim.key());
        require(times.size() == 1 && TimeUnit.MILLISECONDS.toNanos(times.getFirst()) > System.nanoTime() - started);
    }

    private void finish(Claim claim, String reference) {
        require(jdbc.update("""
            update provider_operations set status='ACKNOWLEDGED',provider_reference=?,lease_owner=null,lease_expires_at=null,version=2,manual_review_required=false
            where id=? and lease_owner=? and status='DISPATCHING' and version=1 and lease_expires_at>clock_timestamp()
            """, reference, claim.step(), claim.owner().toString()) == 1);
    }

    private void markUnknown(Claim claim) {
        if (claim == null) return;
        try {
            transactions.requiresNew(() -> jdbc.update("""
                update provider_operations set status='UNKNOWN',lease_owner=null,lease_expires_at=null,version=2
                where id=? and lease_owner=? and status='DISPATCHING' and version=1
                """, claim.step(), claim.owner().toString()));
        } catch (RuntimeException unavailable) {
            // The original durable claim still prevents redispatch if the terminal update is unavailable.
        }
    }

    private boolean exists(String sql, Object... parameters) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(" + sql + ")", Boolean.class, parameters));
    }

    private SubscriptionOperation processing(SubscriptionOperation o) {
        var now = operationalClock.instant();
        return new SubscriptionOperation(o.id(), o.siteId(), o.customerId(), o.subscriptionId(), o.kind(),
            SubscriptionOperation.Status.PROCESSING, o.authorityScope(), o.keyDigest(), o.fingerprint(), o.providerOperationKey(),
            o.expectedRevision(), o.policyVersion(), o.providerReference(), null, o.createdAt(), now, o.version()+1);
    }

    static SetupIntent createIntent(StripeClient client, Authority a, UUID operation, String key) throws StripeException {
        var request = SetupIntentCreateParams.builder().setCustomer(a.customerReference()).setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
            .addAllowedPaymentMethodType(SetupIntentCreateParams.AllowedPaymentMethodType.CARD)
            .putAllMetadata(metadata(a, operation)).build();
        var remote = client.v1().setupIntents().create(request, options(key));
        requireIntent(remote, a, operation);
        require("requires_payment_method".equals(remote.getStatus()) && remote.getPaymentMethod() == null);
        return remote;
    }

    static String confirmedCard(StripeClient client, Authority a, UUID operation, String reference, long created) throws StripeException {
        var remote = client.v1().setupIntents().retrieve(reference, options(null));
        requireIntent(remote, a, operation);
        String method = remote.getPaymentMethod();
        require(reference.equals(remote.getId()) && Objects.equals(created, remote.getCreated())
            && "succeeded".equals(remote.getStatus()) && method != null && method.matches("pm_[A-Za-z0-9]{1,252}"));
        requireCard(client.v1().paymentMethods().retrieve(method, options(null)), a, method);
        return method;
    }

    static void installDefault(StripeClient client, Authority a, Created original, String method, String key) throws StripeException {
        var reply = client.v1().subscriptions().update(original.subscription(),
            SubscriptionUpdateParams.builder().setDefaultPaymentMethod(method).build(), options(key));
        require(reply != null && original.subscription().equals(reply.getId()) && "subscription".equals(reply.getObject())
            && Boolean.FALSE.equals(reply.getLivemode()) && a.customerReference().equals(reply.getCustomer())
            && a.clockReference().equals(reply.getTestClock()) && "paused".equals(reply.getStatus())
            && method.equals(reply.getDefaultPaymentMethod()) && reply.getDefaultSource() == null);
    }

    static Map<String,String> metadata(Authority a, UUID operation) {
        var map = new HashMap<>(a.metadata());
        map.put("operation_id", operation.toString());
        return Map.copyOf(map);
    }

    static void requireIntent(SetupIntent remote, Authority a, UUID operation) {
        require(remote != null && remote.getId() != null && remote.getId().matches("seti_[A-Za-z0-9]{1,250}")
            && "setup_intent".equals(remote.getObject()) && Boolean.FALSE.equals(remote.getLivemode())
            && a.customerReference().equals(remote.getCustomer()) && "off_session".equals(remote.getUsage())
            && List.of("card").equals(remote.getPaymentMethodTypes()) && remote.getCreated() != null && remote.getCreated() > 0
            && metadata(a, operation).equals(remote.getMetadata()));
    }

    static void requireCard(PaymentMethod card, Authority a, String method) {
        require(card != null && method.equals(card.getId()) && "payment_method".equals(card.getObject())
            && Boolean.FALSE.equals(card.getLivemode()) && a.customerReference().equals(card.getCustomer())
            && "card".equals(card.getType()) && card.getCard() != null);
    }

    private static void require(boolean valid) {
        if (!valid) throw unavailable();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Optional trial SETUP could not be verified");
    }
}
