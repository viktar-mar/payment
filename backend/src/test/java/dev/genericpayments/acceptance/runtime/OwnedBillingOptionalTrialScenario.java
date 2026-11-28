package dev.genericpayments.acceptance.runtime;

import com.google.gson.JsonObject;
import dev.genericpayments.platform.catalog.domain.PriceId;
import dev.genericpayments.platform.customers.application.port.out.CustomerSessionResolver;
import dev.genericpayments.platform.customers.domain.CustomerId;
import dev.genericpayments.platform.customers.domain.CustomerSessionId;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialAcceptance;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialAdmission;
import dev.genericpayments.platform.provider.adapter.out.stripe.billing.OwnedOptionalTrialSetup.SetupPermit;
import dev.genericpayments.platform.sites.domain.SiteId;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionActor;
import dev.genericpayments.platform.subscriptions.application.model.SubscriptionCommandContext;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionAuthorityPort;
import dev.genericpayments.platform.subscriptions.application.port.out.SubscriptionTransactionPort;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationContext;

import static dev.genericpayments.acceptance.runtime.OwnedBillingOptionalTrialRuntime.stopped;
import static dev.genericpayments.acceptance.runtime.OwnedBillingOptionalTrialRuntime.uuid;

/** Binds later control calls to the original customer and the admitted lifecycle session. */
final class OwnedBillingOptionalTrialScenario implements OwnedBillingOptionalTrialRuntime.Operations {
    private record CustomerScope(SiteId site, CustomerId customer, String origin, String token, CustomerSessionId session) {
        @Override public String toString() { return "OwnedBillingOptionalTrialScenario.CustomerScope[redacted]"; }
    }

    private final OwnedBillingOptionalTrialRuntime.Admission admission;
    private final OwnedOptionalTrialAcceptance acceptance;
    private final CustomerSessionResolver sessions;
    private final SubscriptionAuthorityPort authority;
    private final SubscriptionTransactionPort transactions;
    private final Clock utc;
    private final Instant deadline;
    private OwnedOptionalTrialAcceptance.Session session;
    private CustomerScope customer;
    private boolean opened;
    private boolean terminal;

    OwnedBillingOptionalTrialScenario(OwnedBillingOptionalTrialRuntime.Admission admission, ApplicationContext beans,
                                      Clock utc, Instant deadline) {
        this.admission = admission;
        this.acceptance = beans.getBean(OwnedOptionalTrialAcceptance.class);
        this.sessions = beans.getBean(CustomerSessionResolver.class);
        this.authority = beans.getBean(SubscriptionAuthorityPort.class);
        this.transactions = beans.getBean(SubscriptionTransactionPort.class);
        this.utc = utc;
        this.deadline = deadline;
    }

    @Override public synchronized JsonObject dispatch(String phase, JsonObject body) {
        check();
        if (phase.equals("open")) {
            open(body);
            check();
            return response(null);
        }
        if (session == null || customer == null) throw stopped();
        SetupPermit permit = null;
        switch (phase) {
            case "advance" -> {
                context(phase);
                session.advance();
            }
            case "setup-permit" -> permit = session.prepareSetup(context("setup"),true);
            case "setup-install" -> {
                context(phase);
                session.completeSetup();
            }
            case "resume" -> session.resume(context("resume"),true);
            case "pay" -> {
                context(phase);
                session.pay();
                customer = null;
            }
            default -> throw stopped();
        }
        check();
        return response(permit);
    }

    private void open(JsonObject body) {
        if (opened || !body.get("recurringConsent").getAsBoolean()) throw stopped();
        opened = true;
        var site = new SiteId(uuid(text(body,"siteId")));
        var customerId = new CustomerId(uuid(text(body,"customerId")));
        var token = text(body,"customerToken");
        var origin = text(body,"origin");
        var actor = sessions.resolveRequired(token,origin);
        if (!actor.siteId().equals(site) || !actor.customerId().equals(customerId) || !actor.origin().equals(origin)) throw stopped();
        customer = new CustomerScope(site,customerId,origin,token,actor.sessionId());
        var request = new OwnedOptionalTrialAdmission.Request(admission.run(),new PaymentAccountId(uuid(text(body,"accountId"))),
            site,customerId,new PriceId(uuid(text(body,"priceId"))),origin,token,key("create"),true);
        session = acceptance.open(request);
    }

    private SubscriptionCommandContext context(String purpose) {
        var actor = sessions.resolveRequired(customer.token(),customer.origin());
        if (!customer.session().equals(actor.sessionId())) throw stopped();
        var context = new SubscriptionCommandContext(new SubscriptionActor.Customer(actor),customer.site(),customer.customer(),
            customer.origin(),key(purpose),admission.run().toString());
        transactions.required(() -> {
            authority.revalidate(context,true);
            return null;
        });
        return context;
    }

    private String key(String purpose) {
        return "optional-trial-"+purpose+"-"+admission.run();
    }

    private JsonObject response(SetupPermit permit) {
        var snapshot = session.snapshot();
        var scope = snapshot.scope();
        var result = new JsonObject();
        result.addProperty("stage",snapshot.status().name());
        result.addProperty("runId",scope.run().toString());
        result.addProperty("clockIntentId",scope.clockIntent().toString());
        result.addProperty("subscriptionId",scope.subscription().toString());
        result.addProperty("createOperationId",scope.createOperation().toString());
        result.addProperty("frozenTime",session.frozen());
        result.addProperty("trialEnd",session.trialEnd());
        if (snapshot.setupOperation() != null) result.addProperty("setupOperationId",snapshot.setupOperation().toString());
        if (snapshot.resumeOperation() != null) result.addProperty("resumeOperationId",snapshot.resumeOperation().toString());
        if (permit != null) {
            result.addProperty("setupIntentId",permit.reference());
            result.addProperty("publishableKey",permit.publishableKey());
            result.addProperty("clientSecret",permit.clientSecret());
            result.addProperty("expiresAt",permit.expiresAt().toString());
        }
        return result;
    }

    @Override public synchronized void stop() {
        terminal = true;
        customer = null;
        if (session != null) session.stop();
    }

    private void check() {
        if (terminal || !utc.instant().isBefore(deadline)) throw stopped();
    }

    private static String text(JsonObject body, String name) {
        var value = body.get(name).getAsString();
        if (value.isBlank() || value.codePoints().anyMatch(Character::isISOControl)) throw stopped();
        return value;
    }
}
