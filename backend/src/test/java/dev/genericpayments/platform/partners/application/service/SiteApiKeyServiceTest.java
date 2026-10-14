package dev.genericpayments.platform.partners.application.service;

import static org.assertj.core.api.Assertions.*;
import dev.genericpayments.platform.partners.PartnerTestFixture;
import dev.genericpayments.platform.partners.domain.*;
import dev.genericpayments.platform.partners.application.port.out.*;
import dev.genericpayments.platform.partners.adapter.out.security.Argon2SiteApiKeyAdapter;
import dev.genericpayments.platform.support.PostgresIntegrationTest.TestClock;
import dev.genericpayments.platform.shared.application.port.out.RandomTokenPort;
import dev.genericpayments.platform.sites.application.port.out.LoadSitePort;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SiteApiKeyServiceTest extends PartnerTestFixture {
    @Autowired SiteApiKeyPort store;
    @Autowired LoadSitePort sites;
    @Autowired Clock clock;
    @Autowired RandomTokenPort random;
    @Test void creationHashesOnlySecretAndRevealsFullKeyOnce() {
        var reveal = keys.create(site, Set.of(PartnerScope.PAYMENTS_CREATE, PartnerScope.PAYMENTS_READ), null);
        assertThat(reveal.apiKey()).matches("gpk_[A-Za-z0-9_-]{12}_[A-Za-z0-9_-]{43}");
        var stored = store.findByPrefix(reveal.apiKey().substring(4, 16)).orElseThrow();
        assertThat(stored.keyHash()).startsWith("$argon2id$v=19$m=16384,t=2,p=1$");
        assertThat(new Argon2SiteApiKeyAdapter().matches(reveal.apiKey().substring(17), stored.keyHash())).isTrue();
        assertThat(stored.toString()).doesNotContain(stored.keyHash(), reveal.apiKey(), reveal.apiKey().substring(17));
        assertThat(reveal.toString()).doesNotContain(reveal.apiKey(), reveal.apiKey().substring(17));
        assertThat(jdbc.queryForList("select * from site_api_keys").toString()).doesNotContain(reveal.apiKey(), reveal.apiKey().substring(17));
        assertThat(keys.authenticate(reveal.apiKey()).siteId()).isEqualTo(site);
        assertThat(stored.lastUsedAt()).isNull();
    }
    @Test void fullAnchoredParsingDoesNotSplitBase64urlUnderscores() {
        var reveal = keys.create(site, Set.of(PartnerScope.PAYMENTS_READ), null);
        for (var malformed : List.of(reveal.apiKey() + "x", "x" + reveal.apiKey(), reveal.apiKey() + "\n", "gpk_short_" + "A".repeat(43), "gpk_" + "A".repeat(12) + "_" + "!".repeat(43))) code(() -> keys.authenticate(malformed), "SITE_API_KEY_INVALID");
        var custom = new SiteApiKeyService(store, new Argon2SiteApiKeyAdapter(), bytes -> "_".repeat(bytes == 9 ? 12 : 43), sites, clock);
        var underscored = custom.create(site, Set.of(PartnerScope.PAYMENTS_READ), null);
        assertThat(custom.authenticate(underscored.apiKey()).keyId()).isEqualTo(underscored.key().id());
    }
    @Test void unknownAndMalformedFullShapeDoDummyVerification() {
        var hash = new Argon2SiteApiKeyAdapter(); var checks = new ArrayList<String>();
        SiteApiKeyHashPort probe = new SiteApiKeyHashPort() {
            public String hash(String secret) { return hash.hash(secret); }
            public boolean matches(String secret, String encoded) { checks.add(encoded); return hash.matches(secret, encoded); }
            public String dummyHash() { return hash.dummyHash(); }
        };
        var service = new SiteApiKeyService(store, probe, random, sites, clock);
        code(() -> service.authenticate("gpk_" + "A".repeat(12) + "_" + "B".repeat(43)), "SITE_API_KEY_INVALID");
        code(() -> service.authenticate("gpk_" + "A".repeat(12) + "_" + "!".repeat(43)), "SITE_API_KEY_INVALID");
        assertThat(checks).containsExactly(hash.dummyHash(), hash.dummyHash());
    }
    @Test void expiryAtNowAndRevokeImmediatelyRejectAuthentication() {
        var reveal = keys.create(site, Set.of(PartnerScope.PAYMENTS_READ), INITIAL_TIME.plusSeconds(1));
        TestClock.set(INITIAL_TIME.plusSeconds(1)); code(() -> keys.authenticate(reveal.apiKey()), "SITE_API_KEY_INVALID");
        TestClock.set(INITIAL_TIME); var active = keys.create(site, Set.of(PartnerScope.PAYMENTS_READ), null);
        keys.revoke(site, active.key().id()); code(() -> keys.authenticate(active.apiKey()), "SITE_API_KEY_INVALID");
        assertThat(store.findByPrefix(active.key().prefix()).orElseThrow().revokedAt()).isEqualTo(INITIAL_TIME);
    }
    @Test void rotationInheritsScopesAndExpiryAndCapsOldExpiry() {
        var expiry = INITIAL_TIME.plus(Duration.ofDays(10));
        var old = keys.create(site, Set.of(PartnerScope.PAYMENTS_CREATE), expiry);
        var rotated = keys.rotate(site, old.key().id());
        assertThat(rotated.key().id()).isNotEqualTo(old.key().id());
        assertThat(rotated.key().expiresAt()).isEqualTo(expiry);
        assertThat(rotated.key().scopes()).containsExactly(PartnerScope.PAYMENTS_CREATE);
        assertThat(store.findByPrefix(old.key().prefix()).orElseThrow().expiresAt()).isEqualTo(INITIAL_TIME.plus(Duration.ofHours(24)));
        TestClock.set(INITIAL_TIME.plus(Duration.ofHours(24)));
        code(() -> keys.authenticate(old.apiKey()), "SITE_API_KEY_INVALID");
        assertThat(keys.authenticate(rotated.apiKey()).keyId()).isEqualTo(rotated.key().id());
    }
    @Test void rotationNeverExtendsShortExpiryAndInvalidKeyCannotRotate() {
        var expiry = INITIAL_TIME.plusSeconds(60); var old = keys.create(site, Set.of(PartnerScope.PAYMENTS_READ), expiry);
        assertThat(keys.rotate(site, old.key().id()).key().expiresAt()).isEqualTo(expiry);
        assertThat(store.findByPrefix(old.key().prefix()).orElseThrow().expiresAt()).isEqualTo(expiry);
        TestClock.set(expiry); code(() -> keys.rotate(site, old.key().id()), "SITE_API_KEY_INVALID");
    }
    @Test void lastUsedWritesOnlyEveryFiveMinutes() {
        var reveal = keys.create(site, Set.of(PartnerScope.PAYMENTS_READ), null); var id = reveal.key().id();
        keys.touch(id); TestClock.set(INITIAL_TIME.plusSeconds(299)); keys.touch(id);
        assertThat(store.findByPrefix(reveal.key().prefix()).orElseThrow().lastUsedAt()).isEqualTo(INITIAL_TIME);
        TestClock.set(INITIAL_TIME.plusSeconds(300)); keys.touch(id);
        assertThat(store.findByPrefix(reveal.key().prefix()).orElseThrow().lastUsedAt()).isEqualTo(INITIAL_TIME.plusSeconds(300));
    }
    @Test void rejectsEmptyScopesAndNonFutureExpiryBeforeInsert() {
        assertThatThrownBy(() -> keys.create(site, Set.of(), null)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> keys.create(site, Set.of(PartnerScope.PAYMENTS_READ), INITIAL_TIME)).isInstanceOf(RuntimeException.class);
        assertThat(count("site_api_keys")).isZero();
    }
    @Test void prefixCollisionsRetryBoundedlyAndFailClosed() {
        var service = new SiteApiKeyService(store, new Argon2SiteApiKeyAdapter(), bytes -> "C".repeat(bytes == 9 ? 12 : 43), sites, clock);
        service.create(site, Set.of(PartnerScope.PAYMENTS_READ), null);
        assertThatThrownBy(() -> service.create(site, Set.of(PartnerScope.PAYMENTS_READ), null)).isInstanceOf(RuntimeException.class);
        assertThat(count("site_api_keys")).isEqualTo(1);
    }
    @Test void disabledSiteAndWrongSecretAreIndistinguishable() {
        var key = key(PartnerScope.PAYMENTS_READ);
        code(() -> keys.authenticate(key.substring(0,17) + "A".repeat(43)), "SITE_API_KEY_INVALID");
        jdbc.update("update sites set status='DISABLED' where id=?", site.value());
        code(() -> keys.authenticate(key), "SITE_API_KEY_INVALID");
    }
}
