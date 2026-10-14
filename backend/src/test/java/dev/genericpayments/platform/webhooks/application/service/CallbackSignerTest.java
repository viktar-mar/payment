package dev.genericpayments.platform.webhooks.application.service;
import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
class CallbackSignerTest {
    private static final String V1="{\"amount_minor\":2000,\"currency\":\"EUR\",\"event_id\":\"10000000-0000-0000-0000-000000000001\",\"event_type\":\"payment.succeeded\",\"external_order_id\":null,\"occurred_at\":\"2024-07-03T09:46:40Z\",\"payment_id\":\"30000000-0000-0000-0000-000000000001\",\"payment_status\":\"SUCCEEDED\",\"schema_version\":1,\"site_id\":\"20000000-0000-0000-0000-000000000001\"}";
    private static final String V2="{\"amount_minor\":2000,\"campaign_key\":\"help-now\",\"currency\":\"EUR\",\"customer_id\":null,\"donation_id\":\"40000000-0000-0000-0000-000000000001\",\"event_id\":\"10000000-0000-0000-0000-000000000001\",\"event_type\":\"payment.succeeded\",\"external_order_id\":null,\"occurred_at\":\"2024-07-03T09:46:40Z\",\"payment_id\":\"30000000-0000-0000-0000-000000000001\",\"payment_status\":\"SUCCEEDED\",\"schema_version\":2,\"site_id\":\"20000000-0000-0000-0000-000000000001\"}";
    private final com.fasterxml.jackson.databind.ObjectMapper mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    private byte[] canonical(String json) {
        return new dev.genericpayments.platform.webhooks.adapter.out.persistence.CanonicalCallbackPayloadAdapter().bytes(
            new dev.genericpayments.platform.webhooks.application.port.out.OutboxPort.Claim(java.util.UUID.fromString("10000000-0000-0000-0000-000000000001"),null,
                java.util.UUID.fromString("20000000-0000-0000-0000-000000000001"),null,1,null,json,1,"test",java.time.Instant.EPOCH,java.time.Instant.EPOCH,null,null));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={1,2})
    void exactGoldenCanonicalBytesAndIndependentSignatures(int version) throws Exception {
        String golden=version==1?V1:V2;
        var node=mapper.readTree(golden);var reversed=mapper.createObjectNode();
        var fields=new java.util.ArrayList<String>();node.fieldNames().forEachRemaining(fields::add);java.util.Collections.reverse(fields);
        fields.forEach(f->reversed.set(f,node.get(f)));
        byte[] bytes=canonical(mapper.writeValueAsString(reversed));
        assertThat(bytes).isEqualTo(golden.getBytes(StandardCharsets.UTF_8));
        assertThat(new CallbackSigner().sign("test-key".getBytes(StandardCharsets.US_ASCII),1720000000L,bytes)).isEqualTo(version==1?
            "aa6ae92e2d8a2f3deb48a6b113d283b8e4c8806b0c05f240e65afa7739172c93":"edaf9baa6849cb9a127483b725f08b2f4883a6366d1b0d6eb43afddb6f8a4d75");
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={1,2})
    void rejectsEveryMissingExtraAndTypeInvalidFieldWithSafeDiagnostics(int version) throws Exception {
        String golden=version==1?V1:V2;var base=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(golden);
        var fields=new java.util.ArrayList<String>();base.fieldNames().forEachRemaining(fields::add);
        for(String field:fields) {
            var missing=base.deepCopy();missing.remove(field);invalid(missing.toString());
            var wrong=base.deepCopy();wrong.putObject(field).put("private", "fixture-private-7q9");invalid(wrong.toString());
        }
        for(String field:java.util.List.of("message","donor_message","email","subject","raw_subject","token","sdk_session","otp","unexpected")) {
            var extra=base.deepCopy();extra.put(field,"fixture-private-7q9");invalid(extra.toString());
        }
        for(String versionText:java.util.List.of("0","3","4294967297","1.5","\"2\"","null"))invalid(golden.replace("\"schema_version\":"+version,"\"schema_version\":"+versionText));
        invalid(golden.replace("\"currency\":\"EUR\"","\"currency\":\"ZZZ\""));
        invalid(golden.replace("\"amount_minor\":2000","\"amount_minor\":0"));
        invalid(golden.replace("\"amount_minor\":2000","\"amount_minor\":2.5"));
        invalid(golden.replace("\"payment_status\":\"SUCCEEDED\"","\"payment_status\":\"FAILED\""));
        invalid(golden.replace("\"schema_version\":"+version,"\"schema_version\":"+version+",\"schema_version\":"+version));
    }
    @Test void v2AllowsNullableOrUuidCustomerButRequiresDonationUuidAndKeyAndNullExternalOrder() {
        assertThatCode(()->canonical(V2.replace("\"customer_id\":null","\"customer_id\":\"50000000-0000-0000-0000-000000000001\""))).doesNotThrowAnyException();
        for(String patch:java.util.List.of(V2.replace("\"customer_id\":null","\"customer_id\":\"bad\""),V2.replace("\"external_order_id\":null","\"external_order_id\":\"order\""),V2.replace("\"help-now\"","\"Bad Key\""),V2.replace("\"40000000-0000-0000-0000-000000000001\"","null")))invalid(patch);
    }
    private void invalid(String json){assertThatThrownBy(()->canonical(json)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid callback payload");}
    @Test void signsTimestampAndExactUnicodeBytesAgainstIndependentVector() {
        byte[] body="{\"event\":\"héllo\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(new CallbackSigner().sign("test-key".getBytes(StandardCharsets.US_ASCII),1720000000L,body))
            .isEqualTo("04c5ec560aab8e24bcbe10f8307a0bd808d73384ec80725c6287acf07efc9a80");
        assertThat(new CallbackSigner().sign("test-key".getBytes(StandardCharsets.US_ASCII),1720000000L,"{\"event\":\"hello\"}".getBytes(StandardCharsets.UTF_8)))
            .isNotEqualTo("04c5ec560aab8e24bcbe10f8307a0bd808d73384ec80725c6287acf07efc9a80");
    }
}
