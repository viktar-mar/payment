package dev.genericpayments.platform.subscriptions.adapter.in.web;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.genericpayments.platform.customers.adapter.in.web.CustomerSessionAuthenticationFilter;
import dev.genericpayments.platform.customers.application.model.CustomerSessionContext;
import dev.genericpayments.platform.customers.application.port.out.CustomerSessionResolver;
import dev.genericpayments.platform.customers.domain.*;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import dev.genericpayments.platform.sites.domain.*;
import dev.genericpayments.platform.paymentaccounts.domain.PaymentAccountId;
import dev.genericpayments.platform.subscriptions.application.model.*;
import dev.genericpayments.platform.subscriptions.application.port.in.SubscriptionConfirmationUseCase;
import java.time.Instant;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.core.context.SecurityContextHolder;
class StripeSubscriptionConfirmationHttpTest {
 final SiteId site=new SiteId(UUID.randomUUID());final CustomerId customer=new CustomerId(UUID.randomUUID());final UUID attempt=UUID.randomUUID();
 final String token="a".repeat(43),capability="bca2_"+"b".repeat(43),secret="pi_owned_secret_canary",root="bga1_"+"c".repeat(43);
 final Instant expiry=Instant.now().plusSeconds(600);final CustomerSessionContext session=new CustomerSessionContext(new CustomerSessionId(UUID.randomUUID()),site,customer,"https://shop.test",Set.of(CustomerSessionScope.SUBSCRIPTION_MANAGE),expiry);
 final String base="/api/v1/customer/subscriptions/"+UUID.randomUUID()+"/operations/"+UUID.randomUUID();
 final SubscriptionConfirmationUseCase use=mock(SubscriptionConfirmationUseCase.class);MockMvc mvc;
 @BeforeEach void setup(){
  var sites=mock(LoadSitePort.class);when(sites.findAll()).thenReturn(List.of(Site.create(site,new SiteKey("shop"),"Shop",new PaymentAccountId(UUID.randomUUID()),Set.of(URI.create("https://shop.test")),Set.of(AuthenticationMode.SITE_TOKEN),PaymentMethodPolicy.automatic())));
  var sessions=mock(CustomerSessionResolver.class);when(sessions.resolveRequired(token,"https://shop.test")).thenReturn(session);
  var mapper=new tools.jackson.databind.ObjectMapper();mvc=MockMvcBuilders.standaloneSetup(new SubscriptionConfirmationController(use)).addFilters(new SubscriptionRequestPolicyFilter(mapper,true),new CustomerSessionAuthenticationFilter(sessions,sites,mapper),new SubscriptionRequestPolicyFilter(mapper,false)).build();
 }
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postTo(String path,String body){return post(base+path).header("Origin",session.origin()).header("Authorization","Bearer "+token).header("Idempotency-Key","command").contentType("application/json").content(body);}
 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getAt(String path){return get(base+path).header("Origin",session.origin()).header("Authorization","Bearer "+token);}
 @Test void missingCurrentIsLiteralJsonNullAndNeverAllocates()throws Exception{mvc.perform(getAt("/confirmation-attempts/current")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json")).andExpect(content().string("null")).andExpect(header().string("Cache-Control","no-store"));verify(use).current(any());verifyNoMoreInteractions(use);}
 @Test void v2SessionOmitsLegacyTokenAndStatusOmitsSecrets()throws Exception{
  when(use.select(any(),eq(root))).thenReturn(new SubscriptionConfirmationUseCase.Session("SERVER_CONFIRM_V2","stripe",SubscriptionClientSession.Flow.PAYMENT,"pk_test_fixture",secret,null,attempt,capability,expiry));
  mvc.perform(postTo("/confirmation-session","{\"action_token\":\""+root+"\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.action_token").doesNotExist()).andExpect(jsonPath("$.capability").value(capability)).andExpect(jsonPath("$.attempt_id").value(attempt.toString()));
  when(use.status(any(),eq(attempt))).thenReturn(new SubscriptionConfirmationUseCase.Status(attempt,SubscriptionConfirmationAttempt.State.DECLINED,true,null,expiry));
  var json=mvc.perform(getAt("/confirmation-attempts/"+attempt)).andExpect(status().isOk()).andExpect(jsonPath("$.successor_attempt_id").value(org.hamcrest.Matchers.nullValue())).andExpect(jsonPath("$.next_action_allowed").doesNotExist()).andReturn().getResponse().getContentAsString();assertThat(json).doesNotContain(secret,capability,root);
 }
 @Test void confirmationResponseAloneCarriesTheInvocationPermission()throws Exception{
  when(use.confirm(any(),eq(attempt),eq(capability),eq("ctoken_original"))).thenReturn(new SubscriptionConfirmationUseCase.Confirm(new SubscriptionConfirmationUseCase.Status(attempt,SubscriptionConfirmationAttempt.State.REQUIRES_ACTION,false,null,expiry),true));
  mvc.perform(postTo("/confirmation-attempts/"+attempt+"/confirm","{\"capability\":\""+capability+"\",\"confirmation_token\":\"ctoken_original\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.next_action_allowed").value(true)).andExpect(jsonPath("$.client_secret").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));
 }
 @Test void strictBodiesAndAbsentOriginAreRejectedBeforeUse()throws Exception{
  for(String body:List.of("{\"force\":true}","{\"capability\":\"a\",\"capability\":\"b\"}"))mvc.perform(postTo("/confirmation-attempts/"+attempt+"/retry",body)).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));
  mvc.perform(getAt("/confirmation-attempts/current").with(r->{r.removeHeader("Origin");return r;})).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));verifyNoInteractions(use);
 }
}
