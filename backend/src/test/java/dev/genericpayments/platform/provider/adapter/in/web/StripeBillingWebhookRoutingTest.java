package dev.genericpayments.platform.provider.adapter.in.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import dev.genericpayments.platform.paymentaccounts.application.port.out.LoadPaymentAccountPort;
import dev.genericpayments.platform.paymentaccounts.domain.*;
import dev.genericpayments.platform.payments.application.port.out.ProviderInboxPort;
import dev.genericpayments.platform.provider.adapter.out.stripe.*;
import dev.genericpayments.platform.webhooks.application.port.in.ReceiveSubscriptionEventUseCase;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;

class StripeBillingWebhookRoutingTest {
    final Instant now=Instant.parse("2026-10-28T12:00:00Z");
    final String secret="whsec_"+"C".repeat(32);
    final PaymentAccount account=PaymentAccount.create(new PaymentAccountId(UUID.randomUUID()),"owned",PaymentProviderType.STRIPE,PaymentAccountMode.SANDBOX,"acct_owned","stripe-sandbox-default");
    final LoadPaymentAccountPort accounts=mock(LoadPaymentAccountPort.class);
    final ProviderInboxPort references=mock(ProviderInboxPort.class);
    final ReceiveSubscriptionEventUseCase billing=mock(ReceiveSubscriptionEventUseCase.class);
    final StripeWebhookVerifier verifier=new StripeWebhookVerifier(new StripeCredentialResolver(new StripeSandboxProperties(true,"sk_test_"+"A".repeat(32),"pk_test_"+"B".repeat(32),secret)));
    StripeWebhookController controller(){when(accounts.findByKey("owned")).thenReturn(Optional.of(account));return new StripeWebhookController(accounts,verifier,references,billing,Clock.fixed(now,ZoneOffset.UTC));}
    MockHttpServletRequest request(String type,String kind,String id)throws Exception{
        byte[] raw=("{\"id\":\"evt_owned\",\"object\":\"event\",\"api_version\":\"2026-09-30.endive\",\"created\":1,\"livemode\":false,\"type\":\""+type+"\",\"data\":{\"object\":{\"id\":\""+id+"\",\"object\":\""+kind+"\",\"livemode\":false,\"client_secret\":\"never-store\"}}}").getBytes(StandardCharsets.UTF_8);
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));mac.update((now.getEpochSecond()+".").getBytes(StandardCharsets.US_ASCII));
        var request=new MockHttpServletRequest("POST","/api/v1/provider-webhooks/stripe/owned");request.setAttribute(StripeWebhookAdmissionFilter.RAW_BODY,raw);request.addHeader("Stripe-Signature","t="+now.getEpochSecond()+",v1="+HexFormat.of().formatHex(mac.doFinal(raw)));return request;
    }
    @ParameterizedTest @CsvSource({"invoice.created,invoice,in_owned,SUBSCRIPTION_INVOICE_CHANGED","customer.subscription.created,subscription,sub_owned,SUBSCRIPTION_CHANGED","setup_intent.succeeded,setup_intent,seti_owned,SUBSCRIPTION_SETUP_CHANGED","subscription_schedule.updated,subscription_schedule,sub_sched_owned,SUBSCRIPTION_SCHEDULE_CHANGED"})
    void authenticatedBillingGoesOnlyToDurableSubscriptionInboxAndDuplicatesAck(String type,String kind,String id,String normalized)throws Exception{
        when(billing.receive(any())).thenReturn(true,false);var c=controller();var request=request(type,kind,id);
        assertThat(c.receive("owned",request).getStatusCode().value()).isEqualTo(204);assertThat(c.receive("owned",request).getStatusCode().value()).isEqualTo(204);
        verify(billing,times(2)).receive(argThat(e->e.eventType().equals(normalized)&&e.objectReference().equals(id)&&e.receivedAt().equals(now)));verifyNoInteractions(references);
    }
    @Test void paymentAndRefundRetainOneOriginalReferenceDestination()throws Exception{var c=controller();for(String[] values:List.of(new String[]{"payment_intent.succeeded","payment_intent","pi_owned"},new String[]{"refund.created","refund","re_owned"})){assertThat(c.receive("owned",request(values[0],values[1],values[2])).getStatusCode().value()).isEqualTo(204);}verify(references,times(2)).insertReference(any());verifyNoInteractions(billing);}
    @Test void durableInsertionFailureDoesNotAckAndLeaksNoStoreDetails()throws Exception{when(billing.receive(any())).thenThrow(new IllegalStateException("private-store-detail"));var result=controller().receive("owned",request("invoice.created","invoice","in_owned"));assertThat(result.getStatusCode().value()).isEqualTo(503);assertThat(String.valueOf(result.getBody())).doesNotContain("private-store-detail","never-store",secret);verifyNoInteractions(references);}
    @Test void invalidAuthenticationCannotUseEmptyLegacyVerificationAsBillingAuthority()throws Exception{var c=controller();var request=request("invoice.created","invoice","in_owned");request.removeHeader("Stripe-Signature");assertThat(c.receive("owned",request).getStatusCode().value()).isEqualTo(400);verifyNoInteractions(references,billing);}
}
