package dev.genericpayments.acceptance.runtime;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.MethodParameter;

class OwnedSubscriptionRuntimeTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private OwnedSubscriptionRuntime.BusinessClock persisted(Instant initial,java.nio.file.Path path) throws Exception {
        var method=java.util.Arrays.stream(OwnedSubscriptionRuntime.BusinessClock.class.getMethods()).filter(m->m.getName().equals("persisted")).findFirst();
        assertThat(method).as("owned business time survives a process restart").isPresent();
        try{return (OwnedSubscriptionRuntime.BusinessClock)method.get().invoke(null,initial,path);}
        catch(java.lang.reflect.InvocationTargetException failure){throw (Exception)failure.getCause();}
    }
    @Test void persistedBusinessTimeReloadsExactlyAndRemainsMonotonic() throws Exception {
        var initial=Instant.parse("2030-01-01T00:00:00.123456789Z");var path=directory.resolve("instant.txt");
        var clock=persisted(initial,path);clock.advance(Instant.parse("2030-02-01T00:00:00.987654321Z"));
        assertThat(java.nio.file.Files.readString(path)).isEqualTo("2030-02-01T00:00:00.987654321Z");
        var restarted=persisted(Instant.parse("2040-01-01T00:00:00Z"),path);
        assertThat(restarted.instant()).isEqualTo(Instant.parse("2030-02-01T00:00:00.987654321Z"));
        assertThatThrownBy(()->restarted.advance(initial)).isInstanceOf(IllegalArgumentException.class);
        assertThat(restarted.instant()).isEqualTo(clock.instant());
    }
    @Test void failedDurableWriteDoesNotPublishAnAdvance() throws Exception {
        var initial=Instant.parse("2030-01-01T00:00:00Z");var path=directory.resolve("instant.txt");var clock=persisted(initial,path);
        java.nio.file.Files.delete(path);java.nio.file.Files.createDirectory(path);java.nio.file.Files.writeString(path.resolve("blocker"),"owned fixture");
        assertThatThrownBy(()->clock.advance(initial.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThat(clock.instant()).isEqualTo(initial);
    }
    @Test void malformedOrSymlinkPersistedStateFailsClosed() throws Exception {
        var path=directory.resolve("instant.txt");java.nio.file.Files.writeString(path,"not an instant");
        assertThatThrownBy(()->persisted(Instant.now(),path)).isInstanceOf(IllegalStateException.class);
        var link=directory.resolve("alias.txt");java.nio.file.Files.createSymbolicLink(link,path);
        assertThatThrownBy(()->persisted(Instant.now(),link)).isInstanceOf(IllegalStateException.class);
    }
    static void dependency(Clock clock) {}
    @Test void launcherRequiresExplicitOptInAndChannelCapability() {
        assertThatThrownBy(()->OwnedSubscriptionRuntime.requireOptIn(java.util.Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->OwnedSubscriptionRuntime.requireOptIn(java.util.Map.of("PAYMENT_STAGE4_TEST_RUNTIME","true"))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->OwnedSubscriptionRuntime.requireOptIn(java.util.Map.of("PAYMENT_STAGE4_TEST_RUNTIME","true","PAYMENT_STAGE4_CONTROL_KEY","x".repeat(43)))).doesNotThrowAnyException();
    }
    @Test void privateLoopbackChannelRejectsMissingAuthorityAndBackwardsOrUnboundedTime() throws Exception {
        var clock=new OwnedSubscriptionRuntime.BusinessClock(Instant.parse("2030-01-01T00:00:00Z"));
        var capability="test-only-runtime-capability";
        var server=OwnedSubscriptionRuntime.control(clock,capability.getBytes(java.nio.charset.StandardCharsets.UTF_8),new java.net.InetSocketAddress("127.0.0.1",0));
        try(var client=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var uri=java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/time");
            var denied=java.net.http.HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
            assertThat(client.send(denied,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
            var unrelated=java.net.http.HttpRequest.newBuilder(java.net.URI.create(uri+"-unrelated")).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+capability).GET().build();
            assertThat(client.send(unrelated,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
            for(var at:java.util.List.of("2029-01-01T00:00:00Z","2032-01-01T00:00:00Z")) {
                var request=java.net.http.HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+capability).POST(java.net.http.HttpRequest.BodyPublishers.ofString(at)).build();
                assertThat(client.send(request,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(400);
            }
            var request=java.net.http.HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+capability).POST(java.net.http.HttpRequest.BodyPublishers.ofString("2030-02-01T00:00:00Z")).build();
            assertThat(client.send(request,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            assertThat(clock.instant()).isEqualTo(Instant.parse("2030-02-01T00:00:00Z"));
        } finally {server.stop(0);}
    }
    @Test void explicitBillingClockDoesNotReplaceAuthenticationOrTransportClocks() throws Exception {
        var business=Clock.fixed(Instant.parse("2040-01-01T00:00:00Z"),ZoneOffset.UTC);
        var security=Clock.systemUTC();
        var factory=OwnedSubscriptionRuntime.factory(business);factory.registerSingleton("clock",security);
        var dependency=new DependencyDescriptor(new MethodParameter(getClass().getDeclaredMethod("dependency",Clock.class),0),true);
        for(var bean:java.util.List.of("subscriptionFactService","subscriptionQueryService","fakeSubscriptionState","outboxWorker"))
            assertThat(factory.resolveDependency(dependency,bean)).as(bean).isSameAs(business);
        for(var bean:java.util.List.of("customerSessionService","emailOtpService","subscriptionAuthorityAdapter","callbackDeliveryService","unknownFutureBean"))
            assertThat(factory.resolveDependency(dependency,bean)).as(bean).isSameAs(security);
    }
}
