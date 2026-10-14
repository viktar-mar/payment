package dev.genericpayments.platform.customers.adapter.out.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static dev.genericpayments.platform.customers.adapter.out.security.IdentitySecurityFixtures.*;
import dev.genericpayments.platform.customers.application.port.out.IdentityProviderConfigurationStore;
import dev.genericpayments.platform.customers.application.port.out.IdentityProviderConfigurationStore.PublicJwkSet;
import dev.genericpayments.platform.customers.application.port.out.JwksDiagnosticsPort.RefreshState;
import dev.genericpayments.platform.customers.domain.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.net.ssl.SSLContext;

class SafeJwksClientTest {
    private final IdentityProviderConfigurationStore store=mock(IdentityProviderConfigurationStore.class);
    @Test void diagnosticsNeverResolveDnsFetchOrAllocateCacheForUnseenSources() throws Exception {
        try(var fixture=new JwksHttpsFixture()) {
            AtomicInteger resolutions=new AtomicInteger();
            var policy=new JwksAddressPolicy(host->{ resolutions.incrementAndGet(); return new InetAddress[]{InetAddress.getLoopbackAddress()}; },Set.of("test"),Set.of("keys.test"));
            var client=new SafeJwksClient(store,policy,new MutableClock(),fixture.clientContext);
            for(int i=0;i<300;i++) {
                var cfg=configuration(true,new JwksSource.Remote(URI.create(fixture.uri()+"/unread-"+i)),Set.of("RS256"));
                var observed=client.observe(cfg);
                assertThat(observed.state()).isEqualTo(RefreshState.NEVER_ATTEMPTED);
                assertThat(observed.lastSuccessfulRefreshAt()).isNull(); assertThat(observed.compatibleKeys()).isFalse();
            }
            assertThat(resolutions.get()).isZero(); assertThat(fixture.requests.get()).isZero();
            assertThat(client.resolve(remote(fixture),"rsa-key").contains("rsa-key")).isTrue();
        }
    }
    @Test void diagnosticsPublishSuccessfulTimeAndCompatibleCurrentKeysWithoutAnotherRequest() throws Exception {
        try(var fixture=new JwksHttpsFixture()) {
            var client=client(fixture,new MutableClock()); var cfg=remote(fixture);
            client.resolve(cfg,"rsa-key");
            var observed=client.observe(cfg);
            assertThat(observed.lastSuccessfulRefreshAt()).isEqualTo(NOW);
            assertThat(observed.state()).isEqualTo(RefreshState.CURRENT);
            assertThat(observed.compatibleKeys()).isTrue(); assertThat(fixture.requests.get()).isEqualTo(1);
            var incompatible=configuration(true,new JwksSource.Remote(fixture.uri()),Set.of("ES256"));
            assertThat(client.observe(incompatible).compatibleKeys()).isFalse();
        }
    }
    @Test void diagnosticsDistinguishStaleFailureAndExactExpiryWithoutFetching() throws Exception {
        try(var fixture=new JwksHttpsFixture()) {
            var clock=new MutableClock(); var client=client(fixture,clock); var cfg=remote(fixture);
            client.resolve(cfg,"rsa-key");
            clock.now=NOW.plusSeconds(900);
            assertThat(client.observe(cfg).state()).isEqualTo(RefreshState.STALE);
            assertThat(fixture.requests.get()).isEqualTo(1);
            fixture.status=503;
            assertThat(client.resolve(cfg,"rsa-key").contains("rsa-key")).isTrue();
            assertThat(client.observe(cfg).state()).isEqualTo(RefreshState.REFRESH_FAILED);
            assertThat(client.observe(cfg).lastSuccessfulRefreshAt()).isEqualTo(NOW);
            clock.now=NOW.plusSeconds(3600);
            assertThat(client.observe(cfg).state()).isEqualTo(RefreshState.REFRESH_FAILED);
            clock.now=NOW.plusSeconds(3601);
            assertThat(client.observe(cfg).state()).isEqualTo(RefreshState.EXPIRED);
            assertThat(client.observe(cfg).compatibleKeys()).isFalse();
            assertThat(fixture.requests.get()).isEqualTo(2);
        }
    }
    @Test void failedInitialRefreshHasSafeFailureStateAndNoSuccessfulTime() throws Exception {
        try(var fixture=new JwksHttpsFixture()) {
            fixture.status=503; var client=client(fixture,new MutableClock()); var cfg=remote(fixture);
            NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->client.resolve(cfg,"rsa-key"));
            var observed=client.observe(cfg);
            assertThat(observed.state()).isEqualTo(RefreshState.REFRESH_FAILED);
            assertThat(observed.lastSuccessfulRefreshAt()).isNull(); assertThat(observed.compatibleKeys()).isFalse();
            assertThat(observed.toString()).doesNotContain("503",fixture.uri().toString());
        }
    }
    @Test void diagnosticsBindToTheCurrentUriAndSkipDisabledStaticAndEmailProviders() throws Exception {
        try(var fixture=new JwksHttpsFixture()) {
            var client=client(fixture,new MutableClock()); var cfg=remote(fixture); client.resolve(cfg,"rsa-key");
            var replacement=configuration(true,new JwksSource.Remote(URI.create(fixture.uri()+"/replacement")),Set.of("RS256"));
            assertThat(client.observe(replacement).state()).isEqualTo(RefreshState.NEVER_ATTEMPTED);
            assertThat(client.observe(replacement).lastSuccessfulRefreshAt()).isNull();
            var email=IdentityProviderConfiguration.create(cfg.siteId(),"email-login",new IdentityProviderConfiguration.EmailOtpConfiguration("test-default","otp-default"));
            for(var nonremote:List.of(cfg.disable(),configuration(true,new JwksSource.StaticPublicJwkSet("static-keys"),Set.of("RS256")),email)) {
                assertThat(client.observe(nonremote).state()).isEqualTo(RefreshState.NOT_APPLICABLE);
                assertThat(client.observe(nonremote).lastSuccessfulRefreshAt()).isNull();
                assertThat(client.observe(nonremote).compatibleKeys()).isFalse();
            }
            assertThat(fixture.requests.get()).isEqualTo(1);
        }
    }
    @Test void passiveDiagnosticsDoNotWaitOnAnInFlightNetworkRefresh() throws Exception {
        CountDownLatch release=new CountDownLatch(1);
        try(var tls=new JwksHttpsFixture(); var wire=new JwksWireFixture(tls,output->{
            if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("fixture release timed out");
            String body=jwks(RSA.toPublicJWK());
            output.write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length()+"\r\n\r\n"+body).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }); var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var client=client(tls,new MutableClock());
            var cfg=configuration(true,new JwksSource.Remote(wire.uri()),Set.of("RS256"));
            var refresh=executor.submit(()->client.resolve(cfg,"rsa-key"));
            try {
                assertThat(wire.requested.await(3,TimeUnit.SECONDS)).isTrue();
                var observed=executor.submit(()->client.observe(cfg)).get(250,TimeUnit.MILLISECONDS);
                assertThat(observed.state().name()).isEqualTo("REFRESHING");
                assertThat(observed.lastSuccessfulRefreshAt()).isNull();
            } finally { release.countDown(); }
            assertThat(refresh.get(3,TimeUnit.SECONDS).contains("rsa-key")).isTrue();
            assertThat(client.observe(cfg).state()).isEqualTo(RefreshState.CURRENT);
        } finally { release.countDown(); }
    }
    @Test void validatesAndCanonicalizesPublicSigningMaterialWithoutPrintingIt() {
        var validated=SafeJwksClient.validateStatic(new PublicJwkSet(jwks(RSA.toPublicJWK(),EC.toPublicJWK())));
        assertThat(validated.contains("rsa-key")).isTrue(); assertThat(validated.algorithm("ec-key")).isEqualTo("ES256");
        assertThat(validated.toString()).isEqualTo("ValidatedPublicJwkSet[redacted]");
        assertThat(validated.publicMaterial().toString()).isEqualTo("PublicJwkSet[redacted]");
        assertThat(SafeJwksClient.validateStatic(validated.publicMaterial()).contains("ec-key")).isTrue();
    }
    @Test void rejectsPrivateSymmetricEncryptionDuplicateUnknownAndWeakKeys() throws Exception {
        for(var keys:List.of(jwks(RSA),jwks(new com.nimbusds.jose.jwk.OctetSequenceKey.Builder(new byte[32]).keyID("symmetric").build()),
            jwks(RSA.toPublicJWK(),RSA.toPublicJWK()),"{\"keys\":[]}","{\"keys\":[{\"kty\":\"unknown\",\"kid\":\"unknown\"}]}","malformed")) rejectStatic(keys);
        for(var changes:List.of(Map.<String,Object>of("kid",""),Map.<String,Object>of("use","enc"),Map.<String,Object>of("alg","HS256"),
            Map.<String,Object>of("alg","ES256"),Map.<String,Object>of("key_ops",List.of("sign")),Map.<String,Object>of("n","AQ"),
            Map.<String,Object>of("x5u","https://other.test"))) {
            var key=new HashMap<>(RSA.toPublicJWK().toJSONObject()); key.putAll(changes); rejectStatic(jsonSet(List.of(key)));
        }
        var missing=new HashMap<>(RSA.toPublicJWK().toJSONObject()); missing.remove("kid"); rejectStatic(jsonSet(List.of(missing)));
        var wrongCurve=new HashMap<>(EC.toPublicJWK().toJSONObject()); wrongCurve.put("crv","P-384"); rejectStatic(jsonSet(List.of(wrongCurve)));
        var wrongPoint=new HashMap<>(EC.toPublicJWK().toJSONObject()); wrongPoint.put("x",Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32])); rejectStatic(jsonSet(List.of(wrongPoint)));
        var noAlg=new HashMap<>(RSA.toPublicJWK().toJSONObject()); noAlg.remove("alg");
        assertThat(SafeJwksClient.validateStatic(new PublicJwkSet(jsonSet(List.of(noAlg)))).algorithm("rsa-key")).isEqualTo("RS256");
    }
    @Test void boundsStaticBodyAndKeyCountAndRejectsDuplicateJsonMembers() {
        rejectStatic(" ".repeat(262145));
        var keys=new ArrayList<Map<String,Object>>();
        for(int i=0;i<21;i++) { var key=new HashMap<>(RSA.toPublicJWK().toJSONObject()); key.put("kid","key-"+i); keys.add(key); }
        rejectStatic(jsonSet(keys));
        rejectStatic("{\"keys\":[],\"keys\":[]}");
        rejectStatic(jwks(RSA.toPublicJWK())+"{}");
    }
    @ParameterizedTest @ValueSource(strings={"0.0.0.0","10.0.0.1","127.0.0.1","169.254.169.254","172.16.0.1","192.168.1.1","100.64.0.1","224.0.0.1","255.255.255.255","::","::1","fe80::1","fc00::1","ff02::1","::ffff:127.0.0.1","::ffff:169.254.169.254","2002:7f00:1::","64:ff9b::a00:1"})
    void blocksUnsafeAddresses(String address) throws Exception {
        var policy=new JwksAddressPolicy(host->new InetAddress[]{InetAddress.getByName(address)},Set.of(),Set.of());
        NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(() -> policy.resolve(URI.create("https://keys.test/keys")));
    }
    @Test void validatesEveryAddressAndUrlAndRestrictsLocalExceptionsToExplicitProfiles() throws Exception {
        var mixed=new JwksAddressPolicy(host->new InetAddress[]{InetAddress.getByName("8.8.8.8"),InetAddress.getLoopbackAddress()},Set.of(),Set.of());
        NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(() -> mixed.resolve(URI.create("https://keys.test/keys")));
        var publicPolicy=new JwksAddressPolicy(host->new InetAddress[]{InetAddress.getByName("8.8.8.8")},Set.of(),Set.of());
        for(String uri:List.of("http://keys.test/keys","https://user@keys.test/keys","https://keys.test/keys#fragment"))
            NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(() -> publicPolicy.resolve(URI.create(uri)));
        var production=new JwksAddressPolicy(host->new InetAddress[]{InetAddress.getLoopbackAddress()},Set.of("production","test"),Set.of("keys.test"));
        NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(() -> production.resolve(URI.create("https://keys.test/keys")));
        var test=localPolicy(); assertThat(test.resolve(URI.create("https://keys.test/keys")).length).isEqualTo(1);
        NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(() -> test.resolve(URI.create("https://other.test/keys")));
    }
    @Test void rejectsDnsRebindingAtTheActualConnectBoundary() throws Exception {
        try(var fixture=new JwksHttpsFixture()) {
            var resolutions=new AtomicInteger();
            var policy=new JwksAddressPolicy(host->new InetAddress[]{resolutions.incrementAndGet()==1?InetAddress.getByName("8.8.8.8"):InetAddress.getLoopbackAddress()},Set.of(),Set.of());
            var client=new SafeJwksClient(store,policy,Clock.fixed(NOW,ZoneOffset.UTC),fixture.clientContext);
            NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(() -> client.resolve(remote(fixture),"rsa-key"));
            assertThat(resolutions.get()).isEqualTo(2); assertThat(fixture.requests.get()).isZero();
        }
    }
    @Test void usesPinnedHttpsWithHostnameVerificationAndNeverFollowsRedirects() throws Exception {
        try(var fixture=new JwksHttpsFixture()) {
            var client=client(fixture,new MutableClock());
            assertThat(client.resolve(remote(fixture),"rsa-key").contains("rsa-key")).isTrue();
            fixture.status=302;
            var redirected=client(fixture,new MutableClock());
            NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(() -> redirected.resolve(remote(fixture),"rsa-key"));
            assertThat(fixture.requests.get()).isEqualTo(2);
        }
    }
    @Test void abortsOversizedStreamingBodiesAndRejectsNonSuccessResponses() throws Exception {
        try(var fixture=new JwksHttpsFixture()) {
            fixture.chunked=true; fixture.body=new byte[262145];
            NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(() -> client(fixture,new MutableClock()).resolve(remote(fixture),"rsa-key"));
            fixture.chunked=false; fixture.status=500; fixture.body="sensitive-response".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(() -> client(fixture,new MutableClock()).resolve(remote(fixture),"rsa-key"));
        }
    }
    @Test void rejectsWrongTlsHostnameAndUntrustedCertificatesBeforeSendingHttp() throws Exception {
        try(var fixture=new JwksHttpsFixture()) {
            var policy=new JwksAddressPolicy(host->new InetAddress[]{InetAddress.getLoopbackAddress()},Set.of("test"),Set.of("wrong.test"));
            var client=new SafeJwksClient(store,policy,new MutableClock(),fixture.clientContext);
            var wrong=configuration(true,new JwksSource.Remote(URI.create(fixture.uri().toString().replace("keys.test","wrong.test"))),Set.of("RS256"));
            NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->client.resolve(wrong,"rsa-key"));
            var untrusted=new SafeJwksClient(store,localPolicy(),new MutableClock());
            NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->untrusted.resolve(remote(fixture),"rsa-key"));
            assertThat(fixture.requests.get()).isZero();
        }
    }
    @Test void remoteMaterialUsesTheSamePrivateKeyAndDuplicateMemberRejection() throws Exception {
        try(var fixture=new JwksHttpsFixture()) {
            for(String body:List.of(jwks(RSA),"{\"keys\":[],\"keys\":[]}")) {
                fixture.body=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->client(fixture,new MutableClock()).resolve(remote(fixture),"rsa-key"));
            }
            assertThat(fixture.requests.get()).isEqualTo(2);
        }
    }
    @Test void enforcesTotalDeadline() throws Exception {
        try(var fixture=new JwksHttpsFixture()) {
            fixture.delayMillis=5000;
            long start=System.nanoTime();
            NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(() -> client(fixture,new MutableClock()).resolve(remote(fixture),"rsa-key"));
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(4));
        }
    }
    @Test void cachesSuccessfulKeysRefreshesAtFifteenMinutesAndExpiresStaleAtSixtyMinutes() throws Exception {
        try(var fixture=new JwksHttpsFixture()) {
            var clock=new MutableClock(); var client=client(fixture,clock); var cfg=remote(fixture);
            client.resolve(cfg,"rsa-key"); client.resolve(cfg,"rsa-key"); assertThat(fixture.requests.get()).isEqualTo(1);
            clock.now=NOW.plusSeconds(900); client.resolve(cfg,"rsa-key"); assertThat(fixture.requests.get()).isEqualTo(2);
            fixture.status=503; clock.now=NOW.plusSeconds(1800);
            assertThat(client.resolve(cfg,"rsa-key").contains("rsa-key")).isTrue();
            clock.now=NOW.plusSeconds(4501);
            NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(() -> client.resolve(cfg,"rsa-key"));
        }
    }
    @Test void unknownKidHasOnlyOneSynchronizedForcedRefreshAndAcceptsRotation() throws Exception {
        try(var fixture=new JwksHttpsFixture(); var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var client=client(fixture,new MutableClock()); var cfg=remote(fixture); client.resolve(cfg,"rsa-key");
            fixture.body=jwks(RSA.toPublicJWK(),EC.toPublicJWK()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var jobs=new ArrayList<Future<Boolean>>();
            for(int i=0;i<8;i++) jobs.add(executor.submit(()->client.resolve(cfg,"ec-key").contains("ec-key")));
            for(var job:jobs) assertThat(job.get(4,TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.requests.get()).isEqualTo(2);
            for(int i=0;i<8;i++) NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(() -> client.resolve(cfg,"unknown"));
            assertThat(fixture.requests.get()).isEqualTo(2);
        }
    }
    @Test void cancellationBeforeStartRecoversAllPermitsAndALaterFetchSucceeds() throws Exception {
        try(var fixture=new JwksHttpsFixture()) {
            var executor=new HeldExecutor(); var slots=new Semaphore(16);
            var cancelled=new ArrayList<Runnable>();
            for(int i=0;i<16;i++) {
                var client=new SafeJwksClient(store,localPolicy(),new MutableClock(),fixture.clientContext,executor,slots);
                var outcome=new CompletableFuture<Boolean>();
                Thread caller=Thread.ofVirtual().start(()->{
                    try {
                        NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->client.resolve(remote(fixture),"rsa-key"));
                        outcome.complete(Thread.currentThread().isInterrupted());
                    } catch(Throwable failure) { outcome.completeExceptionally(failure); }
                });
                var task=executor.queued.poll(5,TimeUnit.SECONDS); assertThat(task).isNotNull(); cancelled.add(task);
                caller.interrupt();
                assertThat(outcome.get(5,TimeUnit.SECONDS)).isTrue(); caller.join();
            }
            assertThat(slots.availablePermits()).isEqualTo(16);
            cancelled.forEach(Runnable::run);
            assertThat(slots.availablePermits()).isEqualTo(16);
            assertThat(fixture.requests.get()).isZero();
            executor.runImmediately=true;
            var later=new SafeJwksClient(store,localPolicy(),new MutableClock(),fixture.clientContext,executor,slots);
            assertThat(later.resolve(remote(fixture),"rsa-key").contains("rsa-key")).isTrue();
            assertThat(slots.availablePermits()).isEqualTo(16);
        }
    }
    @Test void executorRejectionReturnsThePermitAndFailsUniformly() throws Exception {
        var slots=new Semaphore(1);
        var executor=new HeldExecutor(); executor.reject=true;
        var client=new SafeJwksClient(store,localPolicy(),new MutableClock(),SSLContext.getDefault(),executor,slots);
        var cfg=configuration(true,new JwksSource.Remote(URI.create("https://keys.test/keys")),Set.of("RS256"));
        NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->client.resolve(cfg,"rsa-key"));
        assertThat(slots.availablePermits()).isEqualTo(1);
    }
    @Test void queuedTaskDeadlineReturnsPermitWithoutStartingNetworkWork() throws Exception {
        var slots=new Semaphore(1); var executor=new HeldExecutor(); var dnsCalls=new AtomicInteger();
        var policy=new JwksAddressPolicy(host->{ dnsCalls.incrementAndGet(); throw new UnknownHostException(); },Set.of(),Set.of());
        var client=new SafeJwksClient(store,policy,new MutableClock(),SSLContext.getDefault(),executor,slots);
        var cfg=configuration(true,new JwksSource.Remote(URI.create("https://keys.test/keys")),Set.of("RS256"));
        long start=System.nanoTime();
        NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->client.resolve(cfg,"rsa-key"));
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isBetween(Duration.ofSeconds(2),Duration.ofSeconds(4));
        assertThat(slots.availablePermits()).isEqualTo(1);
        var cancelled=executor.queued.poll(); assertThat(cancelled).isNotNull(); cancelled.run();
        assertThat(slots.availablePermits()).isEqualTo(1); assertThat(dnsCalls.get()).isZero();
    }
    @Test void racingStartAndCancellationNeverLeaksOrDoubleReleasesAPermit() throws Exception {
        var slots=new Semaphore(1); var executor=new HeldExecutor();
        for(int i=0;i<32;i++) {
            var entered=new CountDownLatch(1); var finish=new CountDownLatch(1); var race=new CountDownLatch(1);
            var policy=new JwksAddressPolicy(host->{
                entered.countDown();
                for(;;) {
                    try { finish.await(); break; } catch(InterruptedException ignored) { /* Non-cancellable DNS. */ }
                }
                throw new UnknownHostException();
            },Set.of(),Set.of());
            var client=new SafeJwksClient(store,policy,new MutableClock(),SSLContext.getDefault(),executor,slots);
            var cfg=configuration(true,new JwksSource.Remote(URI.create("https://keys.test/keys")),Set.of("RS256"));
            var outcome=new CompletableFuture<Boolean>();
            Thread caller=Thread.ofVirtual().start(()->{
                try {
                    NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->client.resolve(cfg,"rsa-key"));
                    outcome.complete(Thread.currentThread().isInterrupted());
                } catch(Throwable failure) { outcome.completeExceptionally(failure); }
            });
            var task=executor.queued.poll(5,TimeUnit.SECONDS); assertThat(task).isNotNull();
            Thread runner=Thread.ofVirtual().start(()->{
                try { race.await(); task.run(); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            Thread canceller=Thread.ofVirtual().start(()->{
                try { race.await(); caller.interrupt(); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            try {
                race.countDown(); assertThat(outcome.get(5,TimeUnit.SECONDS)).isTrue();
                if(entered.getCount()==0) assertThat(slots.availablePermits()).isZero();
            } finally { finish.countDown(); caller.interrupt(); }
            caller.join(); runner.join(); canceller.join();
            assertThat(slots.availablePermits()).isEqualTo(1);
            task.run(); assertThat(slots.availablePermits()).isEqualTo(1);
        }
    }
    @Test void cancellationOfStartedInterruptionIgnoringDnsRetainsPermitUntilActualCompletion() throws Exception {
        try(var fixture=new JwksHttpsFixture(); var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var slots=new Semaphore(1); var entered=new CountDownLatch(1); var finish=new CountDownLatch(1);
            var policy=new JwksAddressPolicy(host->{
                entered.countDown();
                for(;;) {
                    try { finish.await(); break; } catch(InterruptedException ignored) { /* Simulate non-cancellable platform DNS. */ }
                }
                return new InetAddress[]{InetAddress.getLoopbackAddress()};
            },Set.of("test"),Set.of("keys.test"));
            var client=new SafeJwksClient(store,policy,new MutableClock(),fixture.clientContext,executor,slots);
            var outcome=new CompletableFuture<Boolean>();
            Thread caller=Thread.ofVirtual().start(()->{
                try {
                    NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->client.resolve(remote(fixture),"rsa-key"));
                    outcome.complete(Thread.currentThread().isInterrupted());
                } catch(Throwable failure) { outcome.completeExceptionally(failure); }
            });
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); caller.interrupt();
                assertThat(outcome.get(5,TimeUnit.SECONDS)).isTrue(); caller.join();
                assertThat(slots.availablePermits()).isZero();
                var saturated=new SafeJwksClient(store,localPolicy(),new MutableClock(),fixture.clientContext,executor,slots);
                NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->saturated.resolve(remote(fixture),"rsa-key"));
            } finally { finish.countDown(); caller.interrupt(); }
            assertThat(slots.tryAcquire(5,TimeUnit.SECONDS)).isTrue(); slots.release();
            var later=new SafeJwksClient(store,localPolicy(),new MutableClock(),fixture.clientContext,executor,slots);
            assertThat(later.resolve(remote(fixture),"rsa-key").contains("rsa-key")).isTrue();
            assertThat(slots.availablePermits()).isEqualTo(1);
        }
    }
    @Test void preservesUnicodePathAndQueryWithoutDoubleEncodingExistingEscapes() throws Exception {
        try(var fixture=new JwksHttpsFixture()) {
            for(String target:List.of("/keys/ключ%2Fpart?name=é%20value","/keys/%D0%BA%D0%BB%D1%8E%D1%87%2Fpart?name=%C3%A9%20value")) {
                var uri=URI.create("https://keys.test:"+fixture.uri().getPort()+target);
                var cfg=configuration(true,new JwksSource.Remote(uri),Set.of("RS256"));
                assertThat(client(fixture,new MutableClock()).resolve(cfg,"rsa-key").contains("rsa-key")).isTrue();
                assertThat(fixture.requestTarget.get().equals("/keys/%D0%BA%D0%BB%D1%8E%D1%87%2Fpart?name=%C3%A9%20value")).isTrue();
            }
            assertThat(fixture.requests.get()).isEqualTo(2);
        }
    }
    @ParameterizedTest(name="[{index}]")
    @ValueSource(strings={"length-and-transfer","duplicate-length","conflicting-length","comma-length","invalid-length",
        "gzip-transfer","multiple-transfers","identity-transfer","invalid-chunk-size","chunk-extension","missing-chunk-crlf",
        "truncated-chunk","malformed-trailer","framing-trailer","unsupported-trailer","trailer-declaration"})
    void rejectsMalformedWireFramingWithoutLeakingResponseData(String shape) throws Exception {
        byte[] response=malformedResponse(shape).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        try(var tls=new JwksHttpsFixture(); var wire=new JwksWireFixture(tls,output->output.write(response))) {
            var cfg=configuration(true,new JwksSource.Remote(wire.uri()),Set.of("RS256"));
            NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->client(tls,new MutableClock()).resolve(cfg,"rsa-key"));
            assertThat(wire.requested.await(5,TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void acceptsBoundedChunkedAndCloseDelimitedWireBodiesWithoutTrailers() throws Exception {
        String body=jwks(RSA.toPublicJWK()); int middle=body.length()/2;
        String chunks=Integer.toHexString(middle)+"\r\n"+body.substring(0,middle)+"\r\n"
            +Integer.toHexString(body.length()-middle)+"\r\n"+body.substring(middle)+"\r\n0\r\n\r\n";
        for(String response:List.of("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"+chunks,"HTTP/1.1 200 OK\r\n\r\n"+body)) {
            try(var tls=new JwksHttpsFixture(); var wire=new JwksWireFixture(tls,output->output.write(response.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))) {
                var cfg=configuration(true,new JwksSource.Remote(wire.uri()),Set.of("RS256"));
                assertThat(client(tls,new MutableClock()).resolve(cfg,"rsa-key").contains("rsa-key")).isTrue();
                assertThat(wire.requested.await(5,TimeUnit.SECONDS)).isTrue();
            }
        }
    }
    @Test void slowStreamingCannotResetTheTotalDeadlineOrLeakAPermit() throws Exception {
        byte[] body=jwks(RSA.toPublicJWK()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        try(var tls=new JwksHttpsFixture(); var wire=new JwksWireFixture(tls,output->{
            output.write(("HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            output.flush();
            for(byte value:body) { output.write(value); output.flush(); Thread.sleep(150); }
        }); var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var slots=new Semaphore(1);
            var client=new SafeJwksClient(store,localPolicy(),new MutableClock(),tls.clientContext,executor,slots);
            var cfg=configuration(true,new JwksSource.Remote(wire.uri()),Set.of("RS256"));
            long start=System.nanoTime();
            NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(()->client.resolve(cfg,"rsa-key"));
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isBetween(Duration.ofSeconds(2),Duration.ofSeconds(4));
            assertThat(wire.requested.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(slots.tryAcquire(5,TimeUnit.SECONDS)).isTrue(); slots.release();
            var later=new SafeJwksClient(store,localPolicy(),new MutableClock(),tls.clientContext,executor,slots);
            assertThat(later.resolve(remote(tls),"rsa-key").contains("rsa-key")).isTrue();
        }
    }
    private static String malformedResponse(String shape) {
        String body=jwks(RSA.toPublicJWK()); int length=body.length();
        String chunked=Integer.toHexString(length)+"\r\n"+body+"\r\n0\r\n";
        String prefix="HTTP/1.1 200 OK\r\n";
        return prefix+switch(shape) {
            case "length-and-transfer" -> "Content-Length: "+length+"\r\nTransfer-Encoding: chunked\r\n\r\n"+chunked+"\r\n";
            case "duplicate-length" -> "Content-Length: "+length+"\r\nContent-Length: "+length+"\r\n\r\n"+body;
            case "conflicting-length" -> "Content-Length: "+length+"\r\nContent-Length: "+(length+1)+"\r\n\r\n"+body;
            case "comma-length" -> "Content-Length: "+length+", "+length+"\r\n\r\n"+body;
            case "invalid-length" -> "Content-Length: +"+length+"\r\n\r\n"+body;
            case "gzip-transfer" -> "Transfer-Encoding: gzip\r\n\r\n"+body;
            case "multiple-transfers" -> "Transfer-Encoding: gzip, chunked\r\n\r\n"+chunked+"\r\n";
            case "identity-transfer" -> "Transfer-Encoding: identity\r\n\r\n"+body;
            case "invalid-chunk-size" -> "Transfer-Encoding: chunked\r\n\r\nZ\r\n"+body+"\r\n0\r\n\r\n";
            case "chunk-extension" -> "Transfer-Encoding: chunked\r\n\r\n"+Integer.toHexString(length)+";key=value\r\n"+body+"\r\n0\r\n\r\n";
            case "missing-chunk-crlf" -> "Transfer-Encoding: chunked\r\n\r\n"+Integer.toHexString(length)+"\r\n"+body+"0\r\n\r\n";
            case "truncated-chunk" -> "Transfer-Encoding: chunked\r\n\r\n"+Integer.toHexString(length+1)+"\r\n"+body;
            case "malformed-trailer" -> "Transfer-Encoding: chunked\r\n\r\n"+chunked+"not-a-header\r\n\r\n";
            case "framing-trailer" -> "Transfer-Encoding: chunked\r\n\r\n"+chunked+"Content-Length: 0\r\n\r\n";
            case "unsupported-trailer" -> "Transfer-Encoding: chunked\r\n\r\n"+chunked+"X-Fixture: value\r\n\r\n";
            case "trailer-declaration" -> "Transfer-Encoding: chunked\r\nTrailer: Content-Length\r\n\r\n"+chunked+"\r\n";
            default -> throw new IllegalArgumentException("Unknown fixture shape");
        };
    }
    private static final class HeldExecutor extends AbstractExecutorService {
        final BlockingQueue<Runnable> queued=new LinkedBlockingQueue<>();
        boolean runImmediately;
        boolean reject;
        @Override public void execute(Runnable task) {
            if(reject) throw new RejectedExecutionException("Execution rejected");
            if(runImmediately) task.run(); else queued.add(task);
        }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout,TimeUnit unit) { return false; }
    }
    private SafeJwksClient client(JwksHttpsFixture fixture,Clock clock) { return new SafeJwksClient(store,localPolicy(),clock,fixture.clientContext); }
    private JwksAddressPolicy localPolicy() { return new JwksAddressPolicy(host->new InetAddress[]{InetAddress.getLoopbackAddress()},Set.of("test"),Set.of("keys.test")); }
    private IdentityProviderConfiguration remote(JwksHttpsFixture fixture) { return configuration(true,new JwksSource.Remote(fixture.uri()),Set.of("RS256","ES256")); }
    private static String jsonSet(List<Map<String,Object>> keys) { return com.nimbusds.jose.util.JSONObjectUtils.toJSONString(Map.of("keys",keys)); }
    private static void rejectStatic(String json) { NimbusIdentityTokenVerifierAdapterTest.assertSafeFailure(() -> SafeJwksClient.validateStatic(new PublicJwkSet(json))); }
    static final class MutableClock extends Clock {
        Instant now=NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
