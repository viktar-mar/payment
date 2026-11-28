package dev.genericpayments.platform.acceptance;

import com.google.gson.Gson;
import com.stripe.param.ChargeListParams;
import com.stripe.param.billingportal.ConfigurationUpdateParams;
import com.stripe.net.RequestOptions;
import com.sun.net.httpserver.HttpServer;
import dev.genericpayments.platform.provider.application.model.StripePortalNavigationUrl;
import dev.genericpayments.platform.subscriptions.application.port.in.CreateSubscriptionPortalUseCase;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionPolicyPort;
import dev.genericpayments.platform.subscriptions.application.service.SubscriptionPortalService;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionPolicy;
import dev.genericpayments.platform.support.NativePaidPlanFixture;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PAYMENT_STRIPE_BILLING_CASE",matches="portal")
class StripeBillingPortalNativeIT extends NativePaidPlanFixture {
    @Autowired private SubscriptionPolicyPort policies;
    @Autowired private SubscriptionPortalService portal;
    @Autowired private JdbcTemplate jdbc;

    @Test void hostedReturnPreservesOriginalPaidSubscription() throws Exception {
        var plan = provisionPortalPlan();
        var original = local(plan);
        var charge = capturedCharge(plan);
        var bill = jdbc.queryForMap("select id, billing_record_id from payments where subscription_id=?",plan.subscriptionId());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),2);
        var visits = new AtomicInteger();
        var returningOperation = new AtomicReference<UUID>();
        String path="/portal-return/"+runId();
        String merchant="http://127.0.0.1:"+server.getAddress().getPort()+path;
        server.createContext(path, exchange -> {
            try (exchange) {
                if (!exchange.getRequestMethod().equals("GET") || !exchange.getRequestURI().toString().equals(path)
                        || visits.incrementAndGet()!=1) {
                    exchange.sendResponseHeaders(403,-1);
                    return;
                }
                var operation = returningOperation.getAndSet(null);
                if (operation == null || !original.equals(portal.returned(context(plan,null),plan.subscriptionId(),operation))) {
                    exchange.sendResponseHeaders(403,-1);
                    return;
                }
                byte[] body="<!doctype html><title>Subscription account</title>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control","no-store");
                exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        var portalKey = UUID.randomUUID().toString();
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(portalKey.getBytes(StandardCharsets.US_ASCII)));
        try (AutoCloseable owned = () -> {
            try { closeBoundConfiguration(plan, merchant, digest); }
            catch (Exception | AssertionError failure) { throw new AssertionError("PORTAL_CONFIGURATION_CLEANUP", failure); }
        }) {
            var old=policies.load(plan.site());
            var policy=policies.save(new SubscriptionPolicy(plan.site(),old.version(),true,3,false,true,false,Set.of(merchant)),old.version());
            var navigation=portal.create(new CreateSubscriptionPortalUseCase.Command(context(plan,portalKey), plan.subscriptionId(), merchant));
            assertThat(navigation.simulated()).isFalse();
            assertThat(StripePortalNavigationUrl.valid(navigation.navigationUrl())).isTrue();
            var operation=jdbc.queryForObject("select id from subscription_operations where subscription_id=? and kind='PORTAL' and status='SUCCEEDED'",UUID.class,plan.subscriptionId());
            var proof=Files.readString(Path.of("../infrastructure/stripe-billing-portal-proof.sql")).replaceAll("\\$[0-9]+","?");
            Object[] bindings={plan.account().id().value(),plan.site().value(),plan.customer().id().value(),plan.subscriptionId(),
                plan.createOperationId(),bill.get("billing_record_id"),bill.get("id"),plan.price().value(),policy.version(),digest,
                merchant,Timestamp.from(navigation.expiresAt()),original.revision(),Timestamp.from(original.currentPeriod().start()),Timestamp.from(original.currentPeriod().end())};
            assertThat(jdbc.queryForList(proof,Boolean.class,bindings)).containsExactly(true);
            assertThat(visits.get()).isZero();
            returningOperation.set(operation);
            visitHostedPortal(navigation.navigationUrl(),merchant);
            assertThat(visits.get()).isOne();
            assertThat(Instant.now()).isBefore(navigation.expiresAt());
            assertThat(returningOperation.get()).isNull();
            assertThat(local(plan)).isEqualTo(original);
            assertThat(jdbc.queryForList(proof,Boolean.class,bindings)).containsExactly(true);
            assertThat(capturedCharge(plan)).isEqualTo(charge);
            deliverCallbacks(plan.site(),Set.of(plan.subscriptionId()));
        } finally {
            server.stop(0);
        }
    }
    private void closeBoundConfiguration(PaidPlan plan, String returnUrl, String keyDigest) throws Exception {
        // Session creation can fail after configuration creation was already acknowledged.
        var matches = jdbc.queryForList("""
            select c.configuration_reference from stripe_portal_configurations c
              join stripe_portal_configuration_intents i on i.id=c.intent_id and i.payment_account_id=c.payment_account_id
              join subscription_operations o on o.id=i.owner_operation and o.subscription_id=i.owner_subscription
              join provider_operations d on d.id=c.creation_step and d.payment_account_id=c.payment_account_id
             where c.site_id=? and c.payment_account_id=? and o.subscription_id=? and o.site_id=c.site_id
               and o.customer_id=? and o.kind='PORTAL' and o.key_digest=? and i.policy_version=c.policy_version
               and d.kind='PORTAL_CONFIGURATION_CREATE' and d.status='ACKNOWLEDGED'
               and d.resource_id=i.id and d.provider_reference=c.configuration_reference
            """, String.class, plan.site().value(), plan.account().id().value(), plan.subscriptionId(),
            plan.customer().id().value(), keyDigest);
        if (matches.isEmpty()) return;
        assertThat(matches).hasSize(1);
        String id=matches.getFirst();
        var configurations=sandboxClient().v1().billingPortal().configurations();
        var current=configurations.retrieve(id);
        assertThat(current.getId()).isEqualTo(id);
        assertThat(current.getLivemode()).isFalse();
        assertThat(current.getIsDefault()).isFalse();
        assertThat(current.getDefaultReturnUrl()).isEqualTo(returnUrl);
        var closed=configurations.update(id, ConfigurationUpdateParams.builder().setActive(false).build(),
            RequestOptions.builder().setIdempotencyKey("close-portal-fixture-"+runId()).build());
        assertThat(closed.getId()).isEqualTo(id);
        assertThat(closed.getActive()).isFalse();
    }
    private String capturedCharge(PaidPlan plan) throws Exception {
        var client=sandboxClient();
        var sub=client.v1().subscriptions().retrieve(plan.remoteSubscription());
        assertThat(sub.getLivemode()).isFalse();
        assertThat(sub.getCustomer()).isEqualTo(plan.remoteCustomer());
        assertThat(sub.getStatus()).isEqualTo("active");
        assertThat(sub.getCancelAtPeriodEnd()).isFalse();
        assertThat(sub.getCancelAt()).isNull();
        var charges=client.v1().charges().list(ChargeListParams.builder().setCustomer(plan.remoteCustomer()).setLimit(2L).build());
        assertThat(charges.getHasMore()).isFalse();
        assertThat(charges.getData()).hasSize(1);
        var charge=charges.getData().getFirst();
        assertThat(charge.getLivemode()).isFalse();
        assertThat(charge.getCaptured()).isTrue();
        assertThat(charge.getCurrency()).isEqualTo("eur");
        assertThat(charge.getAmountCaptured()).isEqualTo(2000L);
        assertThat(charge.getAmountRefunded()).isZero();
        return charge.getId();
    }
    private static void visitHostedPortal(String session,String merchant) throws Exception {
        var builder=new ProcessBuilder("node","../examples/demo-site/tests/support/stripe-billing-portal-native.mjs");
        // Only Chrome runtime settings enter the browser process. The one-time checkout socket stays in the JVM.
        var env=builder.environment();
        env.keySet().retainAll(Set.of("HOME","PATH","TMPDIR","LANG","LC_ALL","SYSTEMROOT"));
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        var process=builder.start();
        try {
            try (var input=process.getOutputStream()) {
                input.write(new Gson().toJson(List.of(session,merchant)).getBytes(StandardCharsets.UTF_8));
            }
            assertThat(process.waitFor(90,TimeUnit.SECONDS)).isTrue();
            String marker = new String(process.getInputStream().readNBytes(50),StandardCharsets.US_ASCII).trim();
            if (process.exitValue()!=0) {
                String category = Set.of("INPUT","BROWSER_LAUNCH","HOSTED_DOCUMENT","RETURN_LINK","RETURN_COMMIT","CLEANUP").contains(marker)
                    ? marker : "NO_RESULT";
                throw new AssertionError("PORTAL_" + category);
            }
            assertThat(marker).isEqualTo("HOSTED_RETURN_CONFIRMED");
        } finally { if(process.isAlive()) process.destroyForcibly(); }
    }
}
