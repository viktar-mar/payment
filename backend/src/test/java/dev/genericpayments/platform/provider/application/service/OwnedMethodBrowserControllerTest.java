package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.subscriptions.adapter.in.web.*;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.*;
import dev.genericpayments.platform.subscriptions.domain.SubscriptionOperation;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;

/** Direct controllers + actual services + handwritten inert state. No Spring context/HTTP/DB. */
class OwnedMethodBrowserControllerTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @SuppressWarnings("unchecked") private static <T>T deny(Class<T> type){
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{throw new IllegalStateException("Unrelated port denied");});
    }
    private static CustomerSubscriptionController controller(OwnedMethodBrowserService service){
        return new CustomerSubscriptionController(new SubscriptionHttpUseCases(deny(QuerySubscriptionsUseCase.class),deny(CreateSubscriptionUseCase.class),
            deny(PreviewSubscriptionChangeUseCase.class),deny(ChangeSubscriptionPlanUseCase.class),deny(ClearSubscriptionChangeUseCase.class),deny(CancelSubscriptionUseCase.class),
            deny(WithdrawSubscriptionCancellationUseCase.class),deny(SetupSubscriptionMethodUseCase.class),deny(RecoverSubscriptionUseCase.class),deny(ResumeSubscriptionUseCase.class),
            service,service,deny(CreateSubscriptionPortalUseCase.class),deny(ReconcileSubscriptionUseCase.class)));
    }
    private static void principal(Object principal){SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of()));}
    private static CustomerSessionContext session(OwnedMethodBrowserInertFixture f){return ((SubscriptionActor.Customer)f.context.actor()).session();}
    private static MockHttpServletRequest request(String key){var r=new MockHttpServletRequest();if(key!=null)r.addHeader("Idempotency-Key",key);return r;}
    @Test void originalCustomerUsesActualActionSelectionAndConfirmControllersWithoutOrdinaryQuery(){
        var f=new OwnedMethodBrowserInertFixture();var service=OwnedMethodBrowserServiceTest.service(f);var controller=controller(service);principal(session(f));
        var subscription=f.pending().subscription().id();var operation=f.reservation.setupOperationId();

        var action=controller.action(request(null),subscription,operation);
        var selected=new SubscriptionConfirmationController(service).select(subscription,operation,new SubscriptionConfirmationController.Selection(action.secret()),request("select"));
        var response=controller.confirm(request("confirm"),subscription,operation,new SubscriptionTransport.SubscriptionActionRequest(action.secret()));

        assertThat(selected.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(selected.getBody().protocol()).isEqualTo("BROWSER_CONFIRM_V1");
        assertThat(selected.getBody().flow()).isEqualTo(SubscriptionClientSession.Flow.SETUP);assertThat(selected.getBody().attemptId()).isNull();assertThat(selected.getBody().capability()).isNull();
        assertThat(response.getStatusCode().value()).isEqualTo(202);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var dto=response.getBody();assertThat(dto.operationId()).isEqualTo(operation);assertThat(dto.subscriptionId()).isEqualTo(subscription);assertThat(dto.status()).isEqualTo(SubscriptionOperation.Status.RESERVED);
        assertThat(dto.actionAvailable()).isFalse();assertThat(dto.nextAction()).isNull();assertThat(dto.browserConfirmAllowed()).isTrue();
        assertThat(controller.confirm(request("confirm"),subscription,operation,new SubscriptionTransport.SubscriptionActionRequest(action.secret())).getBody().browserConfirmAllowed()).isFalse();
        assertThat(f.reads).isEqualTo(1);f.noBusinessChanges();
    }
    @Test void legacyControllerSharesOriginalSelectionAndKeepsSecretsRedacted(){
        var f=new OwnedMethodBrowserInertFixture();var service=OwnedMethodBrowserServiceTest.service(f);principal(session(f));
        var subscription=f.pending().subscription().id();var operation=f.reservation.setupOperationId();var action=controller(service).action(request(null),subscription,operation);

        var response=new SubscriptionClientSessionController(service).issue(subscription,operation,new SubscriptionClientSessionController.Request(action.secret()),request("issue"));

        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(response.getBody().flow()).isEqualTo(SubscriptionClientSession.Flow.SETUP);
        assertThat(response.getBody().actionToken()).isEqualTo(action.secret());assertThat(response.getBody().clientSecret()).isEqualTo("seti_owned_secret_INERT");
        assertThat(response.getBody().toString()).doesNotContain(action.secret(),"seti_owned_secret_INERT");
        assertThatThrownBy(()->new SubscriptionConfirmationController(service).select(subscription,operation,new SubscriptionConfirmationController.Selection(action.secret()),request("again"))).isInstanceOf(PaymentApplicationException.class);
        assertThat(f.reads).isEqualTo(1);f.noBusinessChanges();
    }
    @Test void actionNoStoreIsSetByTheActualPolicyFilterAroundTheOriginalController() throws Exception {
        var f=new OwnedMethodBrowserInertFixture();var controller=controller(OwnedMethodBrowserServiceTest.service(f));principal(session(f));
        var subscription=f.pending().subscription().id();var operation=f.reservation.setupOperationId();
        var request=new MockHttpServletRequest("GET","/api/v1/customer/subscriptions/"+subscription+"/operations/"+operation+"/action");
        var response=new MockHttpServletResponse();var mapper=new tools.jackson.databind.ObjectMapper();

        new SubscriptionRequestPolicyFilter(mapper,true).doFilter(request,response,(r,s)->{
            var action=controller.action((jakarta.servlet.http.HttpServletRequest)r,subscription,operation);
            s.getOutputStream().write(mapper.writeValueAsBytes(action));
        });

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var wire=mapper.readTree(response.getContentAsString());assertThat(wire.get("kind").asString()).isEqualTo("PROVIDER_CLIENT");
        assertThat(wire.get("secret").asString()).matches("bga1_[A-Za-z0-9_-]{43}");assertThat(wire.get("expires_at")).isNotNull();
        assertThat(f.reads).isZero();f.noBusinessChanges();
    }
    @Test void foreignCustomerSiteSessionSubscriptionAndOperationCannotUseOriginalActionOrPermission(){
        for(String change:List.of("customer","site","session","subscription","operation")){
            var f=new OwnedMethodBrowserInertFixture();var service=OwnedMethodBrowserServiceTest.service(f);var controller=controller(service);var original=session(f);principal(original);
            var subscription=f.pending().subscription().id();var operation=f.reservation.setupOperationId();var action=controller.action(request(null),subscription,operation);
            var altered=new CustomerSessionContext(change.equals("session")?new CustomerSessionId(UUID.randomUUID()):original.sessionId(),
                change.equals("site")?new dev.genericpayments.platform.sites.domain.SiteId(UUID.randomUUID()):original.siteId(),change.equals("customer")?new CustomerId(UUID.randomUUID()):original.customerId(),original.origin(),original.scopes(),original.expiresAt());
            principal(altered);var requestedSubscription=change.equals("subscription")?UUID.randomUUID():subscription;var requestedOperation=change.equals("operation")?UUID.randomUUID():operation;

            assertThatThrownBy(()->controller.action(request(null),requestedSubscription,requestedOperation)).isInstanceOf(PaymentApplicationException.class);
            principal(original);
            new SubscriptionConfirmationController(service).select(subscription,operation,new SubscriptionConfirmationController.Selection(action.secret()),request("select"));
            principal(altered);
            assertThatThrownBy(()->controller.confirm(request("confirm"),requestedSubscription,requestedOperation,new SubscriptionTransport.SubscriptionActionRequest(action.secret()))).isInstanceOf(PaymentApplicationException.class);

            assertThat(f.consumed).isFalse();assertThat(f.reads).isEqualTo(1);f.noBusinessChanges();
        }
    }
    @Test void generalCustomerQueriesAndPartnerAdminActionScopeStillUseTheOriginalQueryGate(){
        var f=new OwnedMethodBrowserInertFixture();var controller=controller(OwnedMethodBrowserServiceTest.service(f));var subscription=f.pending().subscription().id();var operation=f.reservation.setupOperationId();
        principal(session(f));assertThatThrownBy(()->controller.get(request(null),subscription)).hasMessage("Unrelated port denied");
        var partner=new dev.genericpayments.platform.partners.adapter.in.web.AuthenticatedSite(session(f).siteId(),new dev.genericpayments.platform.partners.domain.SiteApiKeyId(UUID.randomUUID()),Set.of());
        var admin=new dev.genericpayments.platform.admin.application.port.in.AuthenticatedAdmin(new dev.genericpayments.platform.admin.domain.AdminUserId(UUID.randomUUID()),"inert",new dev.genericpayments.platform.admin.domain.AdminSessionId(UUID.randomUUID()),f.now().plusSeconds(60));
        for(Object actor:List.of(partner,admin)){
            principal(actor);var r=request(null);r.addParameter("site_id",session(f).siteId().value().toString());
            assertThatThrownBy(()->controller.action(r,subscription,operation)).hasMessage("Unrelated port denied");
            assertThatThrownBy(()->controller.confirm(r,subscription,operation,new SubscriptionTransport.SubscriptionActionRequest("inert"))).hasMessage("Unrelated port denied");
        }
        assertThat(f.root).isNull();assertThat(f.reads).isZero();f.noBusinessChanges();
    }
}
