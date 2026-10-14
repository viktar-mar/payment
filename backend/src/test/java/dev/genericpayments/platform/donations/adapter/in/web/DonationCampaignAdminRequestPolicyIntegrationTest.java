package dev.genericpayments.platform.donations.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/** Real opaque Admin sessions, CSRF, security filters, MVC and PostgreSQL; no mocked policy. */
class DonationCampaignAdminRequestPolicyIntegrationTest extends CampaignApiTestSupport {
    static Stream<Arguments> streamedShapes() {
        return Stream.of("create", "update", "status")
            .flatMap(shape -> Stream.of(-1L, 0L, 1L).map(length -> Arguments.of(shape, length)));
    }

    static Stream<Arguments> boundaryShapes() {
        return Stream.of("create", "update", "status")
            .flatMap(shape -> Stream.of(16383, 16384, 16385).map(bytes -> Arguments.of(shape, bytes)));
    }

    static Stream<Arguments> decodedShapes() {
        return Stream.of("create", "update", "status")
            .flatMap(shape -> Stream.of("literal", "site").map(segment -> Arguments.of(shape, segment)));
    }

    @ParameterizedTest @MethodSource("decodedShapes")
    void decodedMvcMappingsCannotBypassBodyAdmission(String shape, String segment) throws Exception {
        var mutation = mutation(shape);
        var target = segment.equals("literal") ? mutation.path().replace("donation-campaigns", "donation-%63ampaigns")
            : mutation.path().replace(site, "%" + Integer.toHexString(site.charAt(0)) + site.substring(1));
        var mapped = new Mutation(mutation.method(), target, mutation.body());
        // Establish that this URI is accepted by real MVC/security, not rejected for an unrelated reason.
        api.mvc.perform(authenticated(mapped, mapped.body(), -1))
            .andExpect(status().is(shape.equals("create") ? 201 : 200));
        var before = facts();
        var oversized = mapped.body() + " ".repeat(16385 - mapped.body().getBytes(StandardCharsets.UTF_8).length);
        assertTooLarge(api.mvc.perform(authenticated(mapped, oversized, -1)));
        assertThat(facts()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"create", "update", "status"})
    void declaredOversizeIsRejectedWithoutReadingOrParsingAndWithoutSideEffects(String shape) throws Exception {
        var mutation = mutation(shape);
        var before = facts();
        var request = authenticated(mutation, "not-json-private-body", 16385);
        assertTooLarge(api.mvc.perform(request));
        assertThat(request.bytesRead.get()).isZero();
        assertThat(facts()).isEqualTo(before);
    }

    @ParameterizedTest @MethodSource("streamedShapes")
    void unknownOrMisleadingLengthsReadAtMostMaxPlusOneBeforeRejectingMalformedJson(String shape, long length) throws Exception {
        var mutation = mutation(shape);
        var before = facts();
        // 32 KiB of UTF-8, deliberately malformed JSON. Character count is only 16 KiB.
        var request = authenticated(mutation, "é".repeat(16384), length);
        assertTooLarge(api.mvc.perform(request));
        assertThat(request.bytesRead.get()).isEqualTo(16385);
        assertThat(facts()).isEqualTo(before);
    }

    @ParameterizedTest @MethodSource("boundaryShapes")
    void byteBoundaryAllowsValidJsonThroughMaxAndRejectsMaxPlusOne(String shape, int size) throws Exception {
        var mutation = mutation(shape);
        var before = facts();
        var json = mutation.body();
        // Create/update contain a two-byte UTF-8 character, so Java length is not the byte count.
        var body = json + " ".repeat(size - json.getBytes(StandardCharsets.UTF_8).length);
        assertThat(body.getBytes(StandardCharsets.UTF_8)).hasSize(size);
        if (!shape.equals("status")) assertThat(body.length()).isLessThan(size);
        var request = authenticated(mutation, body, -1);
        var result = api.mvc.perform(request);
        if (size == 16385) {
            assertTooLarge(result);
            assertThat(facts()).isEqualTo(before);
        } else {
            result.andExpect(status().is(shape.equals("create") ? 201 : 200))
                .andExpect(jsonPath("$.version").value(shape.equals("create") ? 0 : 1));
            assertThat(jdbc.queryForObject("select count(*) from idempotency_records", Integer.class))
                .isEqualTo(shape.equals("create") ? 1 : 2);
            assertThat(jdbc.queryForObject("select count(*) from audit_log where resource_type='DONATION_CAMPAIGN'", Integer.class))
                .isEqualTo(shape.equals("create") ? 1 : 2);
        }
        assertThat(request.bytesRead.get()).isEqualTo(size);
    }

    @ParameterizedTest @ValueSource(strings = {"create", "update", "status"})
    void sessionAndCsrfFailuresPrecedeBodyReadingAndCannotMutate(String shape) throws Exception {
        var mutation = mutation(shape);
        var before = facts();
        for (var body : List.of(mutation.body(), "not-json-private-body".repeat(1000))) {
            var anonymous = new BodyRequest(mutation.method(), mutation.path(), body, -1);
            api.mvc.perform(anonymous).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
            assertThat(anonymous.bytesRead.get()).isZero();
            var missingCsrf = new BodyRequest(mutation.method(), mutation.path(), body, -1).cookie(api.session);
            api.mvc.perform(missingCsrf).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
            assertThat(missingCsrf.bytesRead.get()).isZero();
            var invalidCsrf = new BodyRequest(mutation.method(), mutation.path(), body, -1)
                .cookie(api.session, api.xsrf).header("X-XSRF-TOKEN", "invalid");
            api.mvc.perform(invalidCsrf).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
            assertThat(invalidCsrf.bytesRead.get()).isZero();
        }
        assertThat(facts()).isEqualTo(before);
    }

    @Test void contextPathDoesNotBypassTheCampaignBodyPolicy() throws Exception {
        var mutation = mutation("update");
        var before = facts();
        var request = authenticated(new Mutation(mutation.method(), "/payments" + mutation.path(), mutation.body()), "x".repeat(16385), -1)
            .contextPath("/payments");
        assertTooLarge(api.mvc.perform(request));
        assertThat(facts()).isEqualTo(before);
    }

    @Test void campaignReadsAndLegacyAdminSetupWritesAreNotClaimedByThisPolicy() throws Exception {
        var id = create();
        for (var target : List.of(path, path + "/" + id)) {
            api.mvc.perform(new BodyRequest("GET", target, "x".repeat(16385), -1).cookie(api.session))
                .andExpect(status().isOk());
        }
        var legacy = """
            {"key":"legacy-body-policy","provider":"FAKE","mode":"SANDBOX",
             "external_account_id":"fake-account","credentials_ref":"secret-reference"}
            """;
        api.postJson("/api/admin/v1/payment-accounts", legacy + " ".repeat(16385))
            .andExpect(status().isCreated());
    }

    private Mutation mutation(String shape) throws Exception {
        var config = CONFIG.replace("Medical support", "Médical support");
        return switch (shape) {
            case "create" -> new Mutation("POST", path, config);
            case "update" -> new Mutation("PUT", path + "/" + create(), config.replace("}", ",\"expected_version\":0}"));
            case "status" -> new Mutation("POST", path + "/" + create() + "/status", "{\"status\":\"ACTIVE\",\"expected_version\":0}");
            default -> throw new IllegalArgumentException(shape);
        };
    }

    private BodyRequest authenticated(Mutation mutation, String body, long declaredLength) {
        return new BodyRequest(mutation.method(), mutation.path(), body, declaredLength)
            .cookie(api.session, api.xsrf).header("X-XSRF-TOKEN", api.xsrf.getValue());
    }

    private void assertTooLarge(ResultActions result) throws Exception {
        var response = result.andExpect(status().is(413))
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Request-ID", "campaign-body-tests"))
            .andExpect(jsonPath("$.status").value(413))
            .andExpect(jsonPath("$.code").value("DONATION_CAMPAIGN_REQUEST_TOO_LARGE"))
            .andExpect(jsonPath("$.type").value("urn:payment:problem:DONATION_CAMPAIGN_REQUEST_TOO_LARGE"))
            .andExpect(jsonPath("$.request_id").value("campaign-body-tests"))
            .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("not-json-private-body", "Médical", "é");
    }

    private List<List<String>> facts() {
        // Whole rows include version/configuration/timestamps, not just unchanged cardinalities.
        return Stream.of("donation_campaigns", "idempotency_records", "audit_log")
            .map(table -> jdbc.queryForList("select row_to_json(t)::text from " + table + " t order by id", String.class)).toList();
    }

    private record Mutation(String method, String path, String body) {}

    /** Models servlet-reported lengths independently of the actual deterministic byte stream. */
    private static final class BodyRequest extends AbstractMockHttpServletRequestBuilder<BodyRequest> {
        final AtomicInteger bytesRead = new AtomicInteger();
        private final long declaredLength;
        BodyRequest(String method, String path, String body, long declaredLength) {
            super(HttpMethod.valueOf(method));
            this.declaredLength = declaredLength;
            uri(URI.create(path)).contentType("application/json").content(body.getBytes(StandardCharsets.UTF_8))
                .header("Idempotency-Key", "bounded-intent").header("X-Request-ID", "campaign-body-tests");
        }
        @Override protected MockHttpServletRequest createServletRequest(ServletContext servletContext) {
            return new MockHttpServletRequest(servletContext) {
                private ServletInputStream counted;
                @Override public int getContentLength() { return (int) declaredLength; }
                @Override public long getContentLengthLong() { return declaredLength; }
                @Override public ServletInputStream getInputStream() {
                    if (counted == null) {
                        var delegate = super.getInputStream();
                        counted = new ServletInputStream() {
                            @Override public int read() throws IOException {
                                int value = delegate.read();
                                if (value >= 0) bytesRead.incrementAndGet();
                                return value;
                            }
                            @Override public boolean isFinished() { return delegate.isFinished(); }
                            @Override public boolean isReady() { return delegate.isReady(); }
                            @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
                        };
                    }
                    return counted;
                }
            };
        }
    }
}
