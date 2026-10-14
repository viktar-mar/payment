# Foundation and Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first working vertical stage of the generic payment platform: a locally runnable modular-monolith backend and secured admin UI that manage payment accounts, sites, products, and immutable price versions through a fake payment provider.

**Architecture:** The backend is one Spring Boot deployable split into hexagonal business modules. Pure domain and application ports stay free of Spring, JPA, HTTP, and provider SDK types; adapters implement PostgreSQL persistence, REST, security, and fake-provider integration. The Next.js admin is a separate deployable and reaches the backend only through its BFF proxy.

**Tech Stack:** Java 25 LTS, Spring Boot 4.1.1, Maven Wrapper 3.9.11, PostgreSQL 18, Flyway, Spring Data JPA, Spring Security, springdoc-openapi 3.1.0, JUnit Platform, AssertJ, Testcontainers, ArchUnit 1.4.1, Cucumber-JVM 7.34.8, Node.js 24 LTS, pnpm 11.1.3, Next.js 16.3.3, React 19.3.0, TypeScript, Tailwind CSS 4.3, Vitest, Testing Library.

**Spec:** `docs/superpowers/specs/2026-09-12-generic-payment-platform-design.md`

## Global Constraints

- Keep `backend/` and `frontend/` independently buildable; do not initialize Git, commit, or push.
- Use package root `dev.genericpayments.platform` and Maven coordinates `dev.genericpayments:payment-platform-backend`.
- Domain packages must not import Spring, Jakarta Persistence, HTTP, Jackson, Stripe, or database classes.
- Use integer minor units for money and uppercase ISO-4217 currency codes.
- Use UUID identifiers internally and lowercase site/product/price keys matching `[a-z0-9][a-z0-9-]{2,62}`.
- A published Price is immutable; a changed amount or billing policy creates a new version.
- A Customer and all future payment state are isolated by Site; Stage 1 creates no Customer records yet.
- Inject `java.time.Clock` into application services and adapters that create timestamps; production code must not call `Instant.now()` directly.
- Secrets enter through environment variables; `.env.example` contains names and safe local examples only.
- FakePaymentProvider is the only provider implementation in Stage 1; no Stripe account or Stripe SDK is required.
- Every production change follows red-green-refactor; Cucumber covers business journeys, not low-level branches.
- Because the user explicitly requested no Git, every task ends with a local verification checkpoint instead of a commit.

---

## Target File Map

### Backend foundation

- `backend/pom.xml` — versions, dependencies, build plugins, unit/integration test execution.
- `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/*` — reproducible Maven 3.9.11 entrypoint.
- `backend/src/main/java/dev/genericpayments/platform/PaymentPlatformApplication.java` — application entrypoint only.
- `backend/src/main/java/dev/genericpayments/platform/shared/domain/*` — shared value validation and domain exceptions.
- `backend/src/main/java/dev/genericpayments/platform/shared/adapter/in/web/*` — Problem Details mapping and request correlation.
- `backend/src/main/java/dev/genericpayments/platform/shared/adapter/out/time/TimeConfiguration.java` — injectable UTC Clock.
- `backend/src/main/resources/application.yml` — non-secret defaults.
- `backend/src/test/java/dev/genericpayments/platform/ArchitectureRulesTest.java` — hexagonal dependency guardrails.

### Payment-account and site module

- `backend/src/main/java/dev/genericpayments/platform/paymentaccounts/domain/*` — provider account identity and status.
- `backend/src/main/java/dev/genericpayments/platform/paymentaccounts/application/port/*` — account commands and repositories.
- `backend/src/main/java/dev/genericpayments/platform/paymentaccounts/application/service/*` — account use cases.
- `backend/src/main/java/dev/genericpayments/platform/paymentaccounts/adapter/out/persistence/*` — JPA mapping.
- `backend/src/main/java/dev/genericpayments/platform/sites/domain/*` — Site aggregate and policies.
- `backend/src/main/java/dev/genericpayments/platform/sites/application/port/*` — site use-case contracts.
- `backend/src/main/java/dev/genericpayments/platform/sites/application/service/*` — site orchestration.
- `backend/src/main/java/dev/genericpayments/platform/sites/adapter/out/persistence/*` — JPA mapping.

### Catalog and provider module

- `backend/src/main/java/dev/genericpayments/platform/catalog/domain/*` — Product, Price, Money, billing and tax policy.
- `backend/src/main/java/dev/genericpayments/platform/catalog/application/port/*` — catalog commands, queries, persistence, provider synchronization.
- `backend/src/main/java/dev/genericpayments/platform/catalog/application/service/*` — create/publish use cases.
- `backend/src/main/java/dev/genericpayments/platform/catalog/adapter/out/persistence/*` — product, price, and provider-mapping JPA adapters.
- `backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/fake/FakeCatalogProviderAdapter.java` — deterministic fake provider references.

### Admin and API

- `backend/src/main/java/dev/genericpayments/platform/admin/domain/*` — admin user, session, and audit types.
- `backend/src/main/java/dev/genericpayments/platform/admin/application/*` — login, logout, session validation, and audit ports.
- `backend/src/main/java/dev/genericpayments/platform/admin/adapter/out/persistence/*` — admin and audit persistence.
- `backend/src/main/java/dev/genericpayments/platform/admin/adapter/in/web/*` — login/logout controller, session filter, security configuration.
- `backend/src/main/java/dev/genericpayments/platform/{paymentaccounts,sites,catalog}/adapter/in/web/*` — versioned admin REST controllers and DTOs.
- `backend/src/main/resources/db/migration/V1__payment_accounts_and_sites.sql` — provider-account and Site schema.
- `backend/src/main/resources/db/migration/V2__catalog.sql` — Product, Price, and provider-mapping schema.
- `backend/src/main/resources/db/migration/V3__admin_security.sql` — admin user, session, and audit schema.

### Frontend and runtime

- `frontend/package.json`, `frontend/pnpm-workspace.yaml`, `frontend/pnpm-lock.yaml` — frontend workspace.
- `frontend/admin/*` — Next.js application configuration.
- `frontend/admin/src/app/login/page.tsx` — login screen.
- `frontend/admin/src/app/sites/page.tsx` — site list and creation.
- `frontend/admin/src/app/sites/[siteId]/catalog/page.tsx` — products and price versions.
- `frontend/admin/src/app/api/backend/[...path]/route.ts` — same-origin BFF proxy.
- `frontend/admin/src/lib/api/*` — generated OpenAPI types and typed client.
- `infrastructure/compose.yaml` — PostgreSQL, backend, and admin services for Stage 1.
- `infrastructure/.env.example` — safe local configuration template.
- `infrastructure/smoke-stage-1.sh` — executable API smoke path.
- `docs/local-development.md` — exact local commands and Stage 1 limitations.

---

### Task 1: Bootstrap the backend and enforce architectural boundaries

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/mvnw`
- Create: `backend/mvnw.cmd`
- Create: `backend/.mvn/wrapper/maven-wrapper.properties`
- Create: `backend/src/test/java/dev/genericpayments/platform/PaymentPlatformApplicationTest.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/PaymentPlatformApplication.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/adapter/out/time/TimeConfiguration.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/dev/genericpayments/platform/ArchitectureRulesTest.java`

**Interfaces:**
- Produces: executable `PaymentPlatformApplication` and a build where `./mvnw test` is the common backend verification command.
- Produces: package rule `..domain..` may not depend on Spring, Jakarta Persistence, Jackson, PostgreSQL, or Stripe packages.

- [ ] **Step 1: Create the Maven descriptor and failing context test**

Use Spring Boot parent `4.1.1`, `<java.version>25</java.version>`, springdoc `3.1.0`, Cucumber `7.34.8`, and ArchUnit `1.4.1`. Create this complete descriptor so later tasks do not repeatedly reshape the build:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>
  <groupId>dev.genericpayments</groupId>
  <artifactId>payment-platform-backend</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <properties>
    <java.version>25</java.version>
    <springdoc.version>3.1.0</springdoc.version>
    <cucumber.version>7.34.8</cucumber.version>
    <archunit.version>1.4.1</archunit.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
    <dependency><groupId>org.bouncycastle</groupId><artifactId>bcprov-jdk18on</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-flyway</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-api</artifactId><version>${springdoc.version}</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-postgresql</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId><version>${archunit.version}</version><scope>test</scope></dependency>
    <dependency><groupId>io.cucumber</groupId><artifactId>cucumber-java</artifactId><version>${cucumber.version}</version><scope>test</scope></dependency>
    <dependency><groupId>io.cucumber</groupId><artifactId>cucumber-spring</artifactId><version>${cucumber.version}</version><scope>test</scope></dependency>
    <dependency><groupId>io.cucumber</groupId><artifactId>cucumber-junit-platform-engine</artifactId><version>${cucumber.version}</version><scope>test</scope></dependency>
    <dependency><groupId>org.junit.platform</groupId><artifactId>junit-platform-suite</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

The initial test must reference the missing entrypoint:

```java
package dev.genericpayments.platform;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
class PaymentPlatformApplicationTest {
    @Test void contextLoads() {}
}
```

- [ ] **Step 2: Run the test and observe the intentional failure**

Run: `cd backend && mvn -q -Dtest=PaymentPlatformApplicationTest test`

Expected: test compilation fails because `PaymentPlatformApplication` does not exist.

- [ ] **Step 3: Add the entrypoint and non-secret configuration**

```java
package dev.genericpayments.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentPlatformApplication.class, args);
    }
}
```

Create the UTC Clock bean:

```java
package dev.genericpayments.platform.shared.adapter.out.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

Use this complete non-secret `application.yml`; an absent bootstrap password is deliberately empty and disables bootstrap:

```yaml
spring:
  application:
    name: payment-platform-backend
  datasource:
    url: ${PAYMENT_DB_URL:jdbc:postgresql://localhost:5432/payment}
    username: ${PAYMENT_DB_USERNAME:payment}
    password: ${PAYMENT_DB_PASSWORD:}
  flyway:
    enabled: true
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.jdbc.time_zone: UTC
  jackson:
    time-zone: UTC
    property-naming-strategy: SNAKE_CASE
    serialization:
      write-dates-as-timestamps: false
  mvc:
    problemdetails:
      enabled: true

management:
  endpoint:
    health:
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include: health

payment:
  provider: ${PAYMENT_PROVIDER:fake}
  security:
    secure-cookie: ${PAYMENT_SECURE_COOKIE:true}
    idle-timeout: PT8H
    absolute-timeout: PT24H
  admin:
    bootstrap:
      username: ${PAYMENT_ADMIN_BOOTSTRAP_USERNAME:}
      password: ${PAYMENT_ADMIN_BOOTSTRAP_PASSWORD:}
```

- [ ] **Step 4: Generate Maven Wrapper 3.9.11 and run the context test**

Run: `cd backend && mvn -N wrapper:wrapper -Dmaven=3.9.11`

Run: `cd backend && ./mvnw -q -Dtest=PaymentPlatformApplicationTest test`

Expected: PASS.

- [ ] **Step 5: Add the ArchUnit rule**

```java
package dev.genericpayments.platform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "dev.genericpayments.platform",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {
    @ArchTest
    static final ArchRule domain_is_framework_free = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "jakarta.persistence..",
            "com.fasterxml.jackson..", "org.postgresql..", "com.stripe..");
}
```

- [ ] **Step 6: Local checkpoint**

Run: `cd backend && ./mvnw test`

Expected: BUILD SUCCESS with the context and architecture tests passing.

---

### Task 2: Implement PaymentAccount and Site domain/application layers

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/domain/DomainValidationException.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/paymentaccounts/domain/{PaymentAccountId,PaymentAccount,PaymentProviderType,PaymentAccountMode,PaymentAccountStatus}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/paymentaccounts/application/port/in/{CreatePaymentAccountCommand,CreatePaymentAccountUseCase}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/paymentaccounts/application/port/out/{LoadPaymentAccountPort,SavePaymentAccountPort}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/paymentaccounts/application/service/CreatePaymentAccountService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/sites/domain/{SiteId,SiteKey,Site,SiteStatus,AuthenticationMode,PaymentMethod,PaymentMethodPolicy,PaymentMethodPolicyMode}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/sites/application/port/in/{CreateSiteCommand,CreateSiteUseCase,ListSitesUseCase}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/sites/application/port/out/{LoadSitePort,SaveSitePort}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/sites/application/service/SiteService.java`
- Test: `backend/src/test/java/dev/genericpayments/platform/paymentaccounts/domain/PaymentAccountTest.java`
- Test: `backend/src/test/java/dev/genericpayments/platform/sites/domain/SiteTest.java`
- Test: `backend/src/test/java/dev/genericpayments/platform/sites/application/service/SiteServiceTest.java`

**Interfaces:**
- Produces: `PaymentAccount create(CreatePaymentAccountCommand command)`.
- Produces: `Optional<PaymentAccount> LoadPaymentAccountPort.findById(PaymentAccountId id)` and `findByKey(String key)`.
- Produces: `Site create(CreateSiteCommand command)` and `List<Site> list()`.
- Produces: `Optional<Site> LoadSitePort.findById(SiteId id)`, `findByKey(SiteKey key)`, `boolean existsByKey(SiteKey key)`, and `List<Site> findAll()`.

- [ ] **Step 1: Write failing value-object and aggregate tests**

```java
class SiteTest {
    private static final PaymentAccountId ACCOUNT_ID = new PaymentAccountId(UUID.randomUUID());

    @Test
    void createsAnActiveSiteWithNormalizedKeyAndOrigins() {
        Site site = Site.create(new SiteId(UUID.randomUUID()), SiteKey.of("Demo-Site"),
            "Demo Site", ACCOUNT_ID,
            Set.of(URI.create("https://demo.example.com"), URI.create("http://localhost:3000")),
            Set.of(AuthenticationMode.SITE_TOKEN),
            PaymentMethodPolicy.allow(Set.of(PaymentMethod.CARD, PaymentMethod.PAYPAL)));

        assertThat(site.key().value()).isEqualTo("demo-site");
        assertThat(site.status()).isEqualTo(SiteStatus.ACTIVE);
    }

    @Test
    void rejectsOriginWithPathOrInsecureRemoteHttp() {
        assertThatThrownBy(() -> Site.create(new SiteId(UUID.randomUUID()), SiteKey.of("demo"),
            "Demo", ACCOUNT_ID, Set.of(URI.create("http://example.com/checkout")),
            Set.of(AuthenticationMode.SITE_TOKEN), PaymentMethodPolicy.automatic()))
            .isInstanceOf(DomainValidationException.class)
            .hasMessageContaining("allowed origin");
    }
}
```

Also test that a PaymentAccount key is normalized, `credentialsRef` is nonblank, and provider type is `FAKE` or `STRIPE`.

- [ ] **Step 2: Run the domain tests to verify failure**

Run: `cd backend && ./mvnw -q -Dtest=PaymentAccountTest,SiteTest test`

Expected: compilation fails because the domain types do not exist.

- [ ] **Step 3: Implement the domain types**

Use records for IDs and value objects; defensive-copy all sets. Define exact enums:

```java
public enum PaymentProviderType { FAKE, STRIPE }
public enum PaymentAccountMode { SANDBOX, LIVE }
public enum PaymentAccountStatus { ACTIVE, DISABLED }
public enum SiteStatus { ACTIVE, DISABLED }
public enum AuthenticationMode { SITE_TOKEN, OIDC_TOKEN, EMAIL_OTP }
public enum PaymentMethod { CARD, APPLE_PAY, GOOGLE_PAY, PAYPAL }
public enum PaymentMethodPolicyMode { AUTOMATIC, ALLOWLIST }
```

`PaymentMethodPolicy.automatic()` must return an empty immutable allowlist. `allow(Set<PaymentMethod>)` must reject an empty set. `Site.create(...)` validates display name, at least one authentication mode, origins, and a non-null account reference; `SiteService` validates that the referenced account is active. Allow plain HTTP only for `localhost`, `127.0.0.1`, and `[::1]`; origins must contain scheme and host but no path, query, fragment, username, or password.

- [ ] **Step 4: Run domain tests**

Run: `cd backend && ./mvnw -q -Dtest=PaymentAccountTest,SiteTest test`

Expected: PASS.

- [ ] **Step 5: Write failing SiteService tests**

```java
@Test
void refusesDuplicateSiteKey() {
    InMemorySitePort sites = new InMemorySitePort();
    sites.save(existingSite("docs"));
    SiteService service = new SiteService(sites, activeAccountPort());

    assertThatThrownBy(() -> service.create(command("docs")))
        .isInstanceOf(DomainValidationException.class)
        .hasMessage("site key already exists: docs");
}

@Test
void refusesDisabledPaymentAccount() {
    SiteService service = new SiteService(new InMemorySitePort(), disabledAccountPort());
    assertThatThrownBy(() -> service.create(command("demo")))
        .isInstanceOf(DomainValidationException.class)
        .hasMessageContaining("payment account is not active");
}
```

- [ ] **Step 6: Implement use-case records, ports, and services**

`CreateSiteCommand` carries `String key`, `String displayName`, `PaymentAccountId paymentAccountId`, `Set<URI> allowedOrigins`, `Set<AuthenticationMode> authenticationModes`, and `PaymentMethodPolicy paymentMethodPolicy`. `SiteService` implements both `CreateSiteUseCase` and `ListSitesUseCase`; it checks account existence/status and duplicate keys before constructing and saving the aggregate.

`CreatePaymentAccountCommand` carries `String key`, `PaymentProviderType provider`, `PaymentAccountMode mode`, `String externalAccountId`, and `String credentialsRef`. The service rejects duplicate keys and never resolves the secret behind `credentialsRef`.

- [ ] **Step 7: Run service and architecture tests**

Run: `cd backend && ./mvnw -q -Dtest=SiteServiceTest,ArchitectureRulesTest test`

Expected: PASS; ArchUnit reports no framework dependency in domain packages.

- [ ] **Step 8: Local checkpoint**

Run: `cd backend && ./mvnw test`

Expected: BUILD SUCCESS.

---

### Task 3: Implement catalog domain, price versioning, and fake provider sync

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/catalog/domain/{Money,ProductId,ProductKey,Product,ProductStatus,PriceId,PriceKey,Price,PriceStatus,PriceType,BillingInterval,TaxMode}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/catalog/application/port/in/{CreateProductCommand,CreateProductUseCase,CreatePriceCommand,CreatePriceUseCase,PublishPriceUseCase,ListCatalogUseCase}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/catalog/application/port/out/{LoadProductPort,SaveProductPort,LoadPricePort,SavePricePort,CatalogProviderPort,ProviderCatalogMappingPort}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/catalog/application/service/{CreateProductService,CreatePriceService,PublishPriceService,CatalogQueryService}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/catalog/application/model/{ProviderProductRef,ProviderPriceRef,ProviderCatalogMapping}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/fake/FakeCatalogProviderAdapter.java`
- Test: `backend/src/test/java/dev/genericpayments/platform/catalog/domain/{MoneyTest,PriceTest}.java`
- Test: `backend/src/test/java/dev/genericpayments/platform/catalog/application/service/{CreatePriceServiceTest,PublishPriceServiceTest}.java`

**Interfaces:**
- Produces: `Product create(CreateProductCommand command)`.
- Produces: `Price create(CreatePriceCommand command)`; version is allocated by `LoadPricePort.nextVersion(SiteId, PriceKey)`.
- Produces: `Price publish(PriceId id)`; publishing returns the saved published Price after provider mappings are durable.
- Produces: `ProviderProductRef ensureProduct(PaymentAccount account, Product product)` and `ProviderPriceRef createPrice(PaymentAccount account, Product product, Price price)`.
- Produces: `ProviderCatalogMapping find/save` methods keyed by payment account and internal product/price IDs.

- [ ] **Step 1: Write failing money and price tests**

```java
@Test
void subscriptionRequiresIntervalAndNonNegativeTrial() {
    Money money = Money.of(1999, "EUR");
    Price price = Price.draft(new PriceId(UUID.randomUUID()), new SiteId(UUID.randomUUID()),
        new ProductId(UUID.randomUUID()), PriceKey.of("pro-monthly"), 1,
        PriceType.SUBSCRIPTION, money, BillingInterval.MONTH, 14,
        TaxMode.INCLUSIVE, "txcd_10000000");

    assertThat(price.status()).isEqualTo(PriceStatus.DRAFT);
    assertThat(price.money().minorUnits()).isEqualTo(1999);
}

@Test
void publishedPriceCannotBeChanged() {
    Price published = validDraft().publish(Instant.parse("2026-09-12T12:00:00Z"));
    assertThatThrownBy(() -> published.changeAmount(Money.of(2999, "EUR")))
        .isInstanceOf(DomainValidationException.class)
        .hasMessage("published price is immutable");
}
```

Add cases for uppercase currency normalization, zero/negative amounts, ONE_TIME with an interval, SUBSCRIPTION without an interval, and trial days on ONE_TIME.

- [ ] **Step 2: Run the catalog domain tests to verify failure**

Run: `cd backend && ./mvnw -q -Dtest=MoneyTest,PriceTest test`

Expected: compilation fails because catalog domain types are missing.

- [ ] **Step 3: Implement catalog domain**

`Money.of(long, String)` validates `minorUnits > 0` and resolves the code with `Currency.getInstance(code.toUpperCase(Locale.ROOT))`. Product keys and Price keys use the same key rule as SiteKey. Define:

```java
public enum ProductStatus { ACTIVE, ARCHIVED }
public enum PriceStatus { DRAFT, PUBLISHED, ARCHIVED }
public enum PriceType { ONE_TIME, SUBSCRIPTION }
public enum BillingInterval { MONTH, YEAR }
public enum TaxMode { NONE, INCLUSIVE, EXCLUSIVE, STRIPE_TAX }
```

`Price.publish(Instant)` returns a new immutable Price with status `PUBLISHED` and `publishedAt`. Only DRAFT can be edited or published. `Product` and `Price` both carry `SiteId`; services reject a product from another site.

- [ ] **Step 4: Run catalog domain tests**

Run: `cd backend && ./mvnw -q -Dtest=MoneyTest,PriceTest test`

Expected: PASS.

- [ ] **Step 5: Write failing versioning and publication tests**

```java
@Test
void allocatesNextVersionForTheSameSiteAndPriceKey() {
    pricePort.nextVersion = 3;
    Price created = service.create(subscriptionCommand("pro-monthly"));
    assertThat(created.version()).isEqualTo(3);
    assertThat(pricePort.saved).isEqualTo(created);
}

@Test
void publishesOnlyAfterFakeProviderMappingsExist() {
    Price published = publishService.publish(draft.id());
    assertThat(published.status()).isEqualTo(PriceStatus.PUBLISHED);
    assertThat(mappingPort.productRef.value()).startsWith("fake_prod_");
    assertThat(mappingPort.priceRef.value()).startsWith("fake_price_");
}
```

- [ ] **Step 6: Implement catalog use cases and fake provider**

`CreateProductService` verifies that `LoadSitePort.findById(siteId)` returns an active Site. `CreatePriceService` verifies the Product belongs to the requested Site and obtains the version atomically from the persistence port. Inject `Clock` into both services and into `PublishPriceService`; use `clock.instant()` for every persisted timestamp and published-at value.

`PublishPriceService` executes in this order: load draft, load Product, load Site, load active PaymentAccount, ensure provider product mapping, create provider price mapping, persist mapping, publish domain Price, save Price. If provider synchronization fails, the Price remains DRAFT.

The fake adapter must be deterministic and contain no mutable global state:

```java
public final class FakeCatalogProviderAdapter implements CatalogProviderPort {
    public ProviderProductRef ensureProduct(PaymentAccount account, Product product) {
        return new ProviderProductRef("fake_prod_" + product.id().value());
    }

    public ProviderPriceRef createPrice(PaymentAccount account, Product product, Price price) {
        return new ProviderPriceRef("fake_price_" + price.id().value() + "_v" + price.version());
    }
}
```

- [ ] **Step 7: Run catalog service and architecture tests**

Run: `cd backend && ./mvnw -q -Dtest=CreatePriceServiceTest,PublishPriceServiceTest,ArchitectureRulesTest test`

Expected: PASS.

- [ ] **Step 8: Local checkpoint**

Run: `cd backend && ./mvnw test`

Expected: BUILD SUCCESS.

---

### Task 4: Persist PaymentAccount and Site in PostgreSQL

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__payment_accounts_and_sites.sql`
- Create: `backend/src/main/java/dev/genericpayments/platform/paymentaccounts/adapter/out/persistence/{PaymentAccountJpaEntity,PaymentAccountJpaRepository,PaymentAccountPersistenceAdapter}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/sites/adapter/out/persistence/{SiteJpaEntity,SiteJpaRepository,SitePersistenceAdapter}.java`
- Test: `backend/src/test/java/dev/genericpayments/platform/support/PostgresIntegrationTest.java`
- Test: `backend/src/test/java/dev/genericpayments/platform/paymentaccounts/adapter/out/persistence/PaymentAccountPersistenceAdapterTest.java`
- Test: `backend/src/test/java/dev/genericpayments/platform/sites/adapter/out/persistence/SitePersistenceAdapterTest.java`

**Interfaces:**
- Consumes: `LoadPaymentAccountPort`, `SavePaymentAccountPort`, `LoadSitePort`, and `SaveSitePort` from Task 2.
- Produces: PostgreSQL-backed implementations preserving every aggregate field.
- Produces: reusable Testcontainers base with one PostgreSQL 18 container and `@ServiceConnection`.

- [ ] **Step 1: Write the failing persistence round-trip tests**

```java
@Test
void roundTripsSiteCollectionsAndPolicies() {
    PaymentAccount account = accounts.save(activeFakeAccount());
    Site original = sites.save(site(account.id(),
        Set.of(URI.create("https://shop.example.com"), URI.create("http://localhost:3000")),
        Set.of(AuthenticationMode.OIDC_TOKEN, AuthenticationMode.EMAIL_OTP),
        PaymentMethodPolicy.allow(Set.of(PaymentMethod.CARD, PaymentMethod.PAYPAL))));

    Site loaded = sites.findById(original.id()).orElseThrow();
    assertThat(loaded).isEqualTo(original);
}
```

The base test class must use `PostgreSQLContainer<>("postgres:18-alpine")` and Spring Boot `@ServiceConnection`; do not rely on a developer-installed database.

- [ ] **Step 2: Run the persistence tests and observe failure**

Run: `cd backend && ./mvnw -q -Dtest=PaymentAccountPersistenceAdapterTest,SitePersistenceAdapterTest test`

Expected: compilation fails because persistence adapters and schema are absent.

- [ ] **Step 3: Add foundation tables to V1 migration**

Create exact tables and constraints:

```sql
create table payment_accounts (
  id uuid primary key,
  account_key varchar(63) not null unique,
  provider varchar(16) not null check (provider in ('FAKE','STRIPE')),
  account_mode varchar(16) not null check (account_mode in ('SANDBOX','LIVE')),
  account_status varchar(16) not null check (account_status in ('ACTIVE','DISABLED')),
  external_account_id varchar(255) not null,
  credentials_ref varchar(255) not null,
  created_at timestamptz not null,
  version bigint not null default 0
);

create table sites (
  id uuid primary key,
  site_key varchar(63) not null unique,
  display_name varchar(120) not null,
  payment_account_id uuid not null references payment_accounts(id),
  status varchar(16) not null check (status in ('ACTIVE','DISABLED')),
  payment_method_policy varchar(16) not null check (payment_method_policy in ('AUTOMATIC','ALLOWLIST')),
  created_at timestamptz not null,
  updated_at timestamptz not null,
  version bigint not null default 0
);

create table site_allowed_origins (
  site_id uuid not null references sites(id) on delete cascade,
  origin varchar(255) not null,
  primary key (site_id, origin)
);

create table site_authentication_modes (
  site_id uuid not null references sites(id) on delete cascade,
  authentication_mode varchar(24) not null,
  primary key (site_id, authentication_mode)
);

create table site_allowed_payment_methods (
  site_id uuid not null references sites(id) on delete cascade,
  payment_method varchar(24) not null,
  primary key (site_id, payment_method)
);
```

- [ ] **Step 4: Implement JPA entities and mappers**

Use package-private JPA entities. Collections use `@ElementCollection` with the three collection tables. Map domain IDs explicitly; do not annotate domain types. Add `@Version long version` to PaymentAccount and Site entities and map timestamps as `Instant`.

Adapters implement both load and save ports:

```java
@Component
final class SitePersistenceAdapter implements LoadSitePort, SaveSitePort {
    private final SiteJpaRepository repository;

    @Override public Site save(Site site) { return repository.save(SiteJpaEntity.from(site)).toDomain(); }
    @Override public Optional<Site> findById(SiteId id) { return repository.findById(id.value()).map(SiteJpaEntity::toDomain); }
    @Override public Optional<Site> findByKey(SiteKey key) { return repository.findBySiteKey(key.value()).map(SiteJpaEntity::toDomain); }
    @Override public boolean existsByKey(SiteKey key) { return repository.existsBySiteKey(key.value()); }
    @Override public List<Site> findAll() { return repository.findAllByOrderByDisplayNameAsc().stream().map(SiteJpaEntity::toDomain).toList(); }
}
```

- [ ] **Step 5: Run persistence tests**

Run: `cd backend && ./mvnw -q -Dtest=PaymentAccountPersistenceAdapterTest,SitePersistenceAdapterTest test`

Expected: PASS against PostgreSQL 18 Testcontainer; Flyway applies V1.

- [ ] **Step 6: Local checkpoint**

Run: `cd backend && ./mvnw test`

Expected: BUILD SUCCESS.

---

### Task 5: Persist Product, Price versions, and provider mappings

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__catalog.sql`
- Create: `backend/src/main/java/dev/genericpayments/platform/catalog/adapter/out/persistence/{ProductJpaEntity,ProductJpaRepository,ProductPersistenceAdapter}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/catalog/adapter/out/persistence/{PriceJpaEntity,PriceJpaRepository,PricePersistenceAdapter}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/catalog/adapter/out/persistence/{ProviderProductMappingJpaEntity,ProviderPriceMappingJpaEntity,ProviderCatalogMappingPersistenceAdapter}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/fake/FakeProviderConfiguration.java`
- Test: `backend/src/test/java/dev/genericpayments/platform/catalog/adapter/out/persistence/CatalogPersistenceAdapterTest.java`
- Test: `backend/src/test/java/dev/genericpayments/platform/catalog/adapter/out/persistence/PublishedPriceConstraintTest.java`

**Interfaces:**
- Consumes: catalog persistence and provider ports from Task 3.
- Produces: atomic `nextVersion(SiteId, PriceKey)` and unique active-published Price per `(site_id, price_key)`.
- Produces: Spring bean `CatalogProviderPort` for PaymentProviderType.FAKE.

- [ ] **Step 1: Write failing catalog persistence tests**

```java
@Test
void allocatesVersionsWithinSiteAndPriceKey() {
    assertThat(prices.nextVersion(siteId, PriceKey.of("pro-monthly"))).isEqualTo(1);
    prices.save(draftPrice(siteId, productId, "pro-monthly", 1));
    assertThat(prices.nextVersion(siteId, PriceKey.of("pro-monthly"))).isEqualTo(2);
}

@Test
void mappingIsScopedByPaymentAccount() {
    mappings.save(mapping(accountA, productId, priceId, "fake_prod_a", "fake_price_a"));
    assertThat(mappings.findPrice(accountB, priceId)).isEmpty();
}
```

- [ ] **Step 2: Run the tests and observe failure**

Run: `cd backend && ./mvnw -q -Dtest=CatalogPersistenceAdapterTest,PublishedPriceConstraintTest test`

Expected: compilation or migration failure because catalog tables/adapters are absent.

- [ ] **Step 3: Add the V2 catalog migration**

```sql
create table products (
  id uuid primary key,
  site_id uuid not null references sites(id),
  product_key varchar(63) not null,
  name varchar(120) not null,
  description varchar(1000),
  status varchar(16) not null check (status in ('ACTIVE','ARCHIVED')),
  created_at timestamptz not null,
  updated_at timestamptz not null,
  version bigint not null default 0,
  unique (site_id, product_key)
);

create table prices (
  id uuid primary key,
  site_id uuid not null references sites(id),
  product_id uuid not null references products(id),
  price_key varchar(63) not null,
  price_version integer not null check (price_version > 0),
  price_type varchar(16) not null check (price_type in ('ONE_TIME','SUBSCRIPTION')),
  amount_minor bigint not null check (amount_minor > 0),
  currency char(3) not null,
  billing_interval varchar(16),
  trial_days integer not null default 0 check (trial_days >= 0),
  tax_mode varchar(16) not null,
  tax_code varchar(64),
  status varchar(16) not null check (status in ('DRAFT','PUBLISHED','ARCHIVED')),
  published_at timestamptz,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  version bigint not null default 0,
  unique (site_id, price_key, price_version)
);

create unique index uq_prices_one_published
  on prices(site_id, price_key) where status = 'PUBLISHED';

create table price_version_counters (
  site_id uuid not null references sites(id),
  price_key varchar(63) not null,
  last_version integer not null check (last_version > 0),
  primary key (site_id, price_key)
);

create table provider_product_mappings (
  payment_account_id uuid not null references payment_accounts(id),
  product_id uuid not null references products(id),
  provider_product_id varchar(255) not null,
  created_at timestamptz not null,
  primary key (payment_account_id, product_id),
  unique (payment_account_id, provider_product_id)
);

create table provider_price_mappings (
  payment_account_id uuid not null references payment_accounts(id),
  price_id uuid not null references prices(id),
  provider_price_id varchar(255) not null,
  created_at timestamptz not null,
  primary key (payment_account_id, price_id),
  unique (payment_account_id, provider_price_id)
);
```

- [ ] **Step 4: Implement persistence and fake-provider configuration**

Allocate versions atomically with one PostgreSQL statement so two first-time requests cannot both receive version 1:

```sql
insert into price_version_counters(site_id, price_key, last_version)
values (:site_id, :price_key, 1)
on conflict (site_id, price_key)
do update set last_version = price_version_counters.last_version + 1
returning last_version;
```

Annotate `CreatePriceService.create(...)` with `@Transactional` so version allocation and the subsequent Price insert run in one transaction. Annotate `PublishPriceService.publish(...)` likewise so provider mappings and the published Price projection are committed together. A rolled-back create command rolls back the counter update; no in-process lock is used.

`FakeProviderConfiguration` exposes the fake adapter only when `payment.provider=fake`:

```java
@Configuration
@ConditionalOnProperty(name = "payment.provider", havingValue = "fake", matchIfMissing = true)
class FakeProviderConfiguration {
    @Bean CatalogProviderPort catalogProviderPort() {
        return new FakeCatalogProviderAdapter();
    }
}
```

- [ ] **Step 5: Run persistence and publication tests**

Run: `cd backend && ./mvnw -q -Dtest=CatalogPersistenceAdapterTest,PublishedPriceConstraintTest,PublishPriceServiceTest test`

Expected: PASS, including concurrent next-version allocation and one-published-version constraint.

- [ ] **Step 6: Local checkpoint**

Run: `cd backend && ./mvnw test`

Expected: BUILD SUCCESS.

---

### Task 6: Secure the Admin API and persist audit facts

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__admin_security.sql`
- Create: `backend/src/main/java/dev/genericpayments/platform/admin/domain/{AdminUserId,AdminUser,AdminSessionId,AdminSession,AuditAction,AuditEntry}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/admin/application/port/in/{LoginAdminCommand,LoginAdminUseCase,LogoutAdminUseCase}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/admin/application/port/out/{AdminUserPort,AdminSessionPort,AuditLogPort}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/admin/application/service/AdminSessionService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/admin/adapter/out/persistence/{AdminUserJpaEntity,AdminSessionJpaEntity,AuditEntryJpaEntity,AdminPersistenceAdapter}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/admin/adapter/in/web/{AdminAuthController,AdminSessionAuthenticationFilter,SecurityConfiguration,AdminSecurityProperties,AdminBootstrapProperties,AdminBootstrap}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/adapter/in/web/{ApiProblem,ApiExceptionHandler,RequestIdFilter}.java`
- Test: `backend/src/test/java/dev/genericpayments/platform/admin/application/service/AdminSessionServiceTest.java`
- Test: `backend/src/test/java/dev/genericpayments/platform/admin/adapter/in/web/AdminAuthenticationIntegrationTest.java`

**Interfaces:**
- Produces: `POST /api/admin/v1/auth/sessions`, `DELETE /api/admin/v1/auth/session`, and `GET /api/admin/v1/auth/session`.
- Produces: cookie `PAYMENT_ADMIN_SESSION`, opaque 256-bit random token whose SHA-256 hash is stored server-side.
- Produces: `AuditLogPort.append(AuditEntry entry)` for later controllers.

- [ ] **Step 1: Write failing session-service tests**

```java
@Test
void validPasswordCreatesHashedOpaqueSessionAndAuditEntry() {
    LoginResult result = service.login(new LoginAdminCommand("admin", "correct-password"),
        "request-1", "127.0.0.1");

    assertThat(result.rawSessionToken()).hasSizeGreaterThanOrEqualTo(43);
    assertThat(sessionPort.saved().tokenHash()).doesNotContain(result.rawSessionToken());
    assertThat(auditPort.last().action()).isEqualTo(AuditAction.ADMIN_LOGIN_SUCCEEDED);
}

@Test
void invalidPasswordReturnsTheSamePublicFailureAsUnknownUser() {
    assertThatThrownBy(() -> service.login(new LoginAdminCommand("admin", "wrong"), "r1", "127.0.0.1"))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThatThrownBy(() -> service.login(new LoginAdminCommand("missing", "wrong"), "r2", "127.0.0.1"))
        .isInstanceOf(InvalidCredentialsException.class);
}
```

- [ ] **Step 2: Run service tests and observe failure**

Run: `cd backend && ./mvnw -q -Dtest=AdminSessionServiceTest test`

Expected: compilation fails because admin types are absent.

- [ ] **Step 3: Implement admin domain, service, and password/session hashing**

Use `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()` for the bootstrap password; this implementation emits Argon2id hashes and uses the BouncyCastle dependency added in Task 1. Generate raw session bytes with `SecureRandom`, encode base64url without padding, persist only `SHA-256(rawToken)`, and expire sessions using `payment.security.idle-timeout` (8 hours by default) with `payment.security.absolute-timeout` (24 hours by default) as the hard cap. For an unknown username, verify the submitted password against one precomputed dummy Argon2id hash so the public timing path remains comparable.

`LoginResult` contains only the raw token and expiry for the controller; it is never logged. Every success and failure appends an audit entry without the submitted password.

- [ ] **Step 4: Add the V3 admin-security migration**

```sql
create table admin_users (
  id uuid primary key,
  username varchar(120) not null unique,
  password_hash varchar(255) not null,
  status varchar(16) not null check (status in ('ACTIVE','DISABLED')),
  created_at timestamptz not null,
  version bigint not null default 0
);

create table admin_sessions (
  id uuid primary key,
  admin_user_id uuid not null references admin_users(id),
  token_hash char(64) not null unique,
  created_at timestamptz not null,
  last_seen_at timestamptz not null,
  expires_at timestamptz not null,
  absolute_expires_at timestamptz not null,
  revoked_at timestamptz
);

create table audit_log (
  id uuid primary key,
  actor_type varchar(24) not null,
  actor_id varchar(255) not null,
  action varchar(64) not null,
  resource_type varchar(64),
  resource_id varchar(255),
  request_id varchar(64) not null,
  source_ip varchar(64),
  details jsonb not null default '{}'::jsonb,
  occurred_at timestamptz not null
);

create index idx_audit_log_occurred_at on audit_log(occurred_at desc);
```

- [ ] **Step 5: Write failing HTTP authentication tests**

```java
@Test
void protectedEndpointRejectsAnonymousAndLoginSetsSecureCookie() throws Exception {
    mockMvc.perform(get("/api/admin/v1/auth/session"))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(post("/api/admin/v1/auth/sessions")
            .contentType(APPLICATION_JSON)
            .content("""{"username":"admin","password":"local-password"}"""))
        .andExpect(status().isNoContent())
        .andExpect(cookie().httpOnly("PAYMENT_ADMIN_SESSION", true))
        .andExpect(cookie().sameSite("PAYMENT_ADMIN_SESSION", "Strict"));
}
```

- [ ] **Step 6: Implement bootstrap, controller, filter, CSRF, and Problem Details**

`AdminBootstrap` creates the first admin only when no admin exists and both `PAYMENT_ADMIN_BOOTSTRAP_USERNAME` and `PAYMENT_ADMIN_BOOTSTRAP_PASSWORD` are present. It must clear no existing password and never log either value.

Security rules:

```java
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/api/admin/v1/auth/sessions").permitAll()
    .requestMatchers("/api/admin/**").authenticated()
    .anyRequest().denyAll());
```

Bind `AdminSecurityProperties` with `@ConfigurationProperties("payment.security")` (`secureCookie`, `idleTimeout`, `absoluteTimeout`) and `AdminBootstrapProperties` with `@ConfigurationProperties("payment.admin.bootstrap")` (`username`, `password`); register both from `SecurityConfiguration`. Use `CookieCsrfTokenRepository.withHttpOnlyFalse()` plus an explicit `CsrfTokenRequestAttributeHandler` so the Next BFF can copy the token to `X-XSRF-TOKEN`; exempt only the login endpoint. After successful login, `AdminAuthController` resolves the request `CsrfToken` once so Spring writes the `XSRF-TOKEN` cookie in the same response. `PAYMENT_SECURE_COOKIE` defaults to `true`, and only the documented local Docker profile sets it to `false` for plain HTTP. `ApiExceptionHandler` returns `application/problem+json` with `type`, `title`, `status`, `code`, and `request_id`. `RequestIdFilter` accepts a valid 1–64 character `X-Request-Id` or generates a UUID.

- [ ] **Step 7: Run security tests**

Run: `cd backend && ./mvnw -q -Dtest=AdminSessionServiceTest,AdminAuthenticationIntegrationTest test`

Expected: PASS; the database contains an audit entry for successful and failed login.

- [ ] **Step 8: Local checkpoint**

Run: `cd backend && ./mvnw test`

Expected: BUILD SUCCESS.

---

### Task 7: Expose secured admin catalog APIs and a Cucumber journey

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/paymentaccounts/adapter/in/web/{PaymentAccountAdminController,PaymentAccountDto}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/sites/adapter/in/web/{SiteAdminController,CreateSiteRequest,SiteResponse}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/catalog/adapter/in/web/{CatalogAdminController,CreateProductRequest,CreatePriceRequest,ProductResponse,PriceResponse}.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/adapter/in/web/AdminAuditFacade.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/admin/adapter/in/web/AdminCatalogApiIntegrationTest.java`
- Create: `backend/src/test/resources/features/foundation_catalog.feature`
- Create: `backend/src/test/java/dev/genericpayments/platform/acceptance/{RunCucumberTest,CucumberSpringConfiguration,AcceptanceWorld,FoundationCatalogSteps}.java`
- Create: `backend/src/test/resources/junit-platform.properties`

**Interfaces:**
- Produces: `GET/POST /api/admin/v1/payment-accounts`.
- Produces: `GET/POST /api/admin/v1/sites`.
- Produces: `GET/POST /api/admin/v1/sites/{siteId}/products`.
- Produces: `POST /api/admin/v1/products/{productId}/prices`, `POST /api/admin/v1/prices/{priceId}/publish`, and `GET /api/admin/v1/sites/{siteId}/catalog`.
- Produces: OpenAPI at `/v3/api-docs` with operation IDs used by the frontend generator.

- [ ] **Step 1: Write a failing authenticated API integration test**

```java
@Test
void adminCreatesSiteProductAndPublishesPrice() throws Exception {
    AuthenticatedAdminCookies cookies = loginAsAdmin();
    String accountId = createFakePaymentAccount(cookies, "local-fake");
    String siteId = createSite(cookies, accountId, "demo-site");
    String productId = createProduct(cookies, siteId, "pro", "Pro Plan");
    String priceId = createMonthlyPrice(cookies, productId, "pro-monthly", 1999, "EUR");

    mockMvc.perform(post("/api/admin/v1/prices/{priceId}/publish", priceId)
            .cookie(cookies.session(), cookies.xsrf())
            .header("X-XSRF-TOKEN", cookies.xsrf().getValue()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.provider_price_id").value(startsWith("fake_price_")));
}

private record AuthenticatedAdminCookies(Cookie session, Cookie xsrf) {}
```

- [ ] **Step 2: Run the API test and observe failure**

Run: `cd backend && ./mvnw -q -Dtest=AdminCatalogApiIntegrationTest test`

Expected: 404 or compilation failure because the controllers are absent.

- [ ] **Step 3: Implement DTOs, controllers, auditing, and OpenAPI annotations**

Use snake_case JSON via `spring.jackson.property-naming-strategy: SNAKE_CASE`. Validate all request fields with Jakarta Validation. Controllers map DTOs to existing command records and never expose `credentialsRef` after creation.

Required response shapes:

```json
{"id":"uuid","key":"demo-site","display_name":"Demo Site","status":"ACTIVE",
 "payment_account_id":"uuid","allowed_origins":["http://localhost:3000"],
 "authentication_modes":["SITE_TOKEN"],"payment_method_policy":{"mode":"AUTOMATIC","methods":[]}}
```

```json
{"id":"uuid","site_id":"uuid","product_id":"uuid","price_key":"pro-monthly",
 "version":1,"type":"SUBSCRIPTION","amount_minor":1999,"currency":"EUR",
 "billing_interval":"MONTH","trial_days":14,"tax_mode":"INCLUSIVE",
 "status":"PUBLISHED","provider_price_id":"fake_price_uuid_v1"}
```

Every mutation calls `AdminAuditFacade.record(...)` with action, resource type/ID, request ID, actor ID, and non-secret details.

- [ ] **Step 4: Run the API test**

Run: `cd backend && ./mvnw -q -Dtest=AdminCatalogApiIntegrationTest test`

Expected: PASS.

- [ ] **Step 5: Write the first Cucumber feature**

```gherkin
Feature: Manage an isolated site catalog

  Scenario: An administrator publishes the first monthly price
    Given an active fake payment account "local-fake"
    And an authenticated administrator
    When the administrator creates site "demo-site"
    And creates product "pro" for site "demo-site"
    And creates monthly price "pro-monthly" for 1999 EUR cents with a 14 day trial
    And publishes price "pro-monthly"
    Then price "pro-monthly" version 1 is published
    And a fake provider product mapping exists
    And a fake provider price mapping exists
    And the audit log contains the catalog changes

  Scenario: A changed amount creates a new draft version
    Given site "demo-site" has published price "pro-monthly" version 1 for 1999 EUR cents
    When the administrator creates price "pro-monthly" for 2499 EUR cents
    Then price "pro-monthly" version 1 remains published for 1999 EUR cents
    And price "pro-monthly" version 2 is draft for 2499 EUR cents
```

The step definitions call HTTP endpoints, not application services, and share a Spring Boot/Testcontainers scenario context. Reset tables between scenarios with ordered SQL deletes; do not recreate the container.

Use this JUnit Platform runner:

```java
package dev.genericpayments.platform.acceptance;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.genericpayments.platform.acceptance")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty,summary")
public class RunCucumberTest {}
```

`CucumberSpringConfiguration` supplies the Spring/Cucumber bridge; `PostgresIntegrationTest` must be `public abstract` so this class can extend it from another package:

```java
@CucumberContextConfiguration
@SpringBootTest
@AutoConfigureMockMvc
public class CucumberSpringConfiguration extends PostgresIntegrationTest {}
```

Store mutable scenario state in a scenario-scoped bean so Cucumber cannot leak it between scenarios:

```java
@Component
@ScenarioScope
public class AcceptanceWorld {
    final Map<String, String> siteIds = new HashMap<>();
    final Map<String, String> productIds = new HashMap<>();
    final Map<String, String> priceIds = new HashMap<>();
    Cookie sessionCookie;
    Cookie xsrfCookie;
    MvcResult lastResponse;
}
```

`FoundationCatalogSteps` constructor-injects this world, `JdbcTemplate`, and `AdminBootstrap`, binds every sentence in the feature to a concrete HTTP call, and makes assertions through JsonPath. Its public step signatures are:

```java
@Given("an active fake payment account {string}") void activeFakeAccount(String key)
@Given("an authenticated administrator") void authenticatedAdministrator()
@When("the administrator creates site {string}") void createSite(String key)
@When("creates product {string} for site {string}") void createProduct(String productKey, String siteKey)
@When("creates monthly price {string} for {long} EUR cents with a {int} day trial")
void createMonthlyPrice(String priceKey, long amountMinor, int trialDays)
@When("publishes price {string}") void publishPrice(String priceKey)
@Then("price {string} version {int} is published") void priceIsPublished(String key, int version)
@Then("a fake provider product mapping exists") void productMappingExists()
@Then("a fake provider price mapping exists") void priceMappingExists()
@Then("the audit log contains the catalog changes") void catalogAuditExists()
@Given("site {string} has published price {string} version {int} for {long} EUR cents")
void existingPublishedPrice(String siteKey, String priceKey, int version, long amountMinor)
@When("the administrator creates price {string} for {long} EUR cents")
void createAnotherPrice(String priceKey, long amountMinor)
@Then("price {string} version {int} remains published for {long} EUR cents")
void publishedVersionRemains(String key, int version, long amountMinor)
@Then("price {string} version {int} is draft for {long} EUR cents")
void draftVersionExists(String key, int version, long amountMinor)
```

Use Cucumber `@Before` to delete `audit_log`, provider mappings, prices, version counters, products, site collections, sites, sessions, users, and payment accounts in foreign-key order, then call `AdminBootstrap.ensureAdminExists()`. Scenario-scoped `AcceptanceWorld` starts empty automatically.

- [ ] **Step 6: Run Cucumber and verify scenarios pass**

Run: `cd backend && ./mvnw -q -Dtest=RunCucumberTest test`

Expected: 2 scenarios and all steps PASS.

- [ ] **Step 7: Verify OpenAPI output**

Start an isolated PostgreSQL container and the backend in a separate terminal:

`docker run --rm --name payment-openapi-postgres -e POSTGRES_DB=payment -e POSTGRES_USER=payment -e POSTGRES_PASSWORD=payment -p 55432:5432 -d postgres:18-alpine`

`cd backend && PAYMENT_DB_URL=jdbc:postgresql://localhost:55432/payment PAYMENT_DB_USERNAME=payment PAYMENT_DB_PASSWORD=payment PAYMENT_PROVIDER=fake PAYMENT_ADMIN_BOOTSTRAP_USERNAME=admin PAYMENT_ADMIN_BOOTSTRAP_PASSWORD=local-password PAYMENT_SECURE_COOKIE=false ./mvnw spring-boot:run`

Then run:

`curl --fail http://localhost:8080/v3/api-docs | jq -e '.paths["/api/admin/v1/sites"]'`

Expected: jq prints the path object and exits 0.

Stop the Spring Boot process with Ctrl-C, then stop the named temporary container:

`docker stop payment-openapi-postgres`

- [ ] **Step 8: Local checkpoint**

Run: `cd backend && ./mvnw test`

Expected: BUILD SUCCESS including Cucumber.

---

### Task 8: Build the Next.js admin for login, sites, and catalog

**Files:**
- Create: `frontend/package.json`
- Create: `frontend/pnpm-workspace.yaml`
- Create: `frontend/admin/package.json`
- Create: `frontend/admin/{next.config.ts,tsconfig.json,postcss.config.mjs,vitest.config.ts}`
- Create: `frontend/admin/src/app/{layout.tsx,globals.css,page.tsx}`
- Create: `frontend/admin/src/app/login/page.tsx`
- Create: `frontend/admin/src/app/sites/page.tsx`
- Create: `frontend/admin/src/app/sites/[siteId]/catalog/page.tsx`
- Create: `frontend/admin/src/app/api/backend/[...path]/route.ts`
- Create: `frontend/admin/src/proxy.ts`
- Create: `frontend/admin/src/lib/api/{client.ts,schema.d.ts}`
- Create: `frontend/admin/src/components/{AppShell,LoginForm,SiteList,CreateSiteForm,CatalogView,CreateProductForm,CreatePriceForm}.tsx`
- Create: `frontend/admin/src/test/setup.ts`
- Test: `frontend/admin/src/components/{LoginForm,SiteList,CatalogView}.test.tsx`

**Interfaces:**
- Consumes: Stage 1 Admin API and generated `/v3/api-docs` schema from Task 7.
- Produces: same-origin browser API `/api/backend/*` forwarding to `BACKEND_INTERNAL_URL`.
- Produces: authenticated routes `/sites` and `/sites/[siteId]/catalog`.

- [ ] **Step 1: Create the workspace manifests and failing component test**

Use `packageManager: "pnpm@11.1.3"`. Pin `next` to `16.3.3`, `react` and `react-dom` to `19.3.0`; use Tailwind `4.3.x`. Set `lint` to `eslint .`, `typecheck` to `tsc --noEmit`, `test` to `vitest`, and `generate:api` to `openapi-typescript http://localhost:8080/v3/api-docs -o src/lib/api/schema.d.ts`.

```tsx
it("renders site identity and opens its catalog", async () => {
  render(<SiteList sites={[{ id: "site-1", key: "demo-site", display_name: "Demo Site", status: "ACTIVE" }]} />);
  expect(screen.getByRole("link", { name: /Demo Site/i }))
    .toHaveAttribute("href", "/sites/site-1/catalog");
});
```

- [ ] **Step 2: Install dependencies and observe the failing test**

Run: `cd frontend && pnpm install && pnpm --filter admin test --run`

Expected: test compilation fails because `SiteList` is absent.

- [ ] **Step 3: Generate the typed API contract and implement the BFF proxy**

Start an isolated PostgreSQL container and the backend in a separate terminal:

`docker run --rm --name payment-openapi-postgres -e POSTGRES_DB=payment -e POSTGRES_USER=payment -e POSTGRES_PASSWORD=payment -p 55432:5432 -d postgres:18-alpine`

`cd backend && PAYMENT_DB_URL=jdbc:postgresql://localhost:55432/payment PAYMENT_DB_USERNAME=payment PAYMENT_DB_PASSWORD=payment PAYMENT_PROVIDER=fake PAYMENT_ADMIN_BOOTSTRAP_USERNAME=admin PAYMENT_ADMIN_BOOTSTRAP_PASSWORD=local-password PAYMENT_SECURE_COOKIE=false ./mvnw spring-boot:run`

Generate types, then stop Spring Boot with Ctrl-C and stop the named container:

`cd frontend && pnpm --filter admin generate:api`

`docker stop payment-openapi-postgres`

The proxy must copy method, safe headers, body, backend `Set-Cookie`, and status; it must never accept a destination URL from the caller:

```ts
const backendBase = process.env.BACKEND_INTERNAL_URL ?? "http://localhost:8080";

async function forward(request: NextRequest, segments: string[]) {
  const target = new URL(`/api/${segments.join("/")}`, backendBase);
  const headers = new Headers(request.headers);
  headers.delete("host");
  const response = await fetch(target, {
    method: request.method,
    headers,
    body: request.method === "GET" || request.method === "HEAD" ? undefined : await request.arrayBuffer(),
    redirect: "manual",
    cache: "no-store",
  });
  const outgoing = new NextResponse(response.body, { status: response.status });
  response.headers.forEach((value, name) => {
    if (name.toLowerCase() !== "set-cookie") outgoing.headers.set(name, value);
  });
  for (const cookie of response.headers.getSetCookie()) outgoing.headers.append("set-cookie", cookie);
  return outgoing;
}
```

Export GET, POST, PUT, PATCH, and DELETE handlers that call `forward` with the catch-all route segments. `client.ts` always calls relative `/api/backend/...`, adds `X-XSRF-TOKEN` from the readable `XSRF-TOKEN` cookie on mutations, parses Problem Details, and never logs response bodies.

- [ ] **Step 4: Implement login and guarded navigation**

`LoginForm` submits `{username,password}` to `/api/backend/admin/v1/auth/sessions`, clears the password state in `finally`, shows the stable error title, and navigates to `/sites` on 204. Root page redirects to `/sites`. Next.js 16 `proxy.ts` may only check cookie presence for navigation convenience; backend remains authoritative. Export a named `proxy(request: NextRequest)` and match `/sites/:path*`.

```ts
export function proxy(request: NextRequest) {
  if (!request.cookies.has("PAYMENT_ADMIN_SESSION")) {
    return NextResponse.redirect(new URL("/login", request.url));
  }
  return NextResponse.next();
}

export const config = { matcher: ["/sites/:path*"] };
```

- [ ] **Step 5: Implement site and catalog pages**

`SiteList` shows key, status, account, origins, auth modes, and payment policy. `CreateSiteForm` loads payment accounts, validates key/origins client-side, and posts the exact Task 7 DTO. `CatalogView` groups products and Price versions, visually distinguishes DRAFT/PUBLISHED/ARCHIVED, and requires a confirmation before publish. Amount input is converted to minor units with currency fraction digits; it never uses binary floating-point multiplication without rounding.

Use a restrained admin layout: neutral slate background, white content surfaces, clear focus rings, responsive single-column forms, and no decorative animation. Tailwind enters through `@import "tailwindcss";` in `globals.css`.

- [ ] **Step 6: Run component tests and type checks**

Run: `cd frontend && pnpm --filter admin test --run`

Run: `cd frontend && pnpm --filter admin typecheck`

Expected: PASS.

- [ ] **Step 7: Build the production bundle**

Run: `cd frontend && pnpm --filter admin build`

Expected: Next.js production build succeeds without type or lint errors.

- [ ] **Step 8: Local checkpoint**

Run: `cd frontend && pnpm --filter admin lint && pnpm --filter admin test --run && pnpm --filter admin build`

Expected: all commands PASS.

---

### Task 9: Package Stage 1 with Docker Compose and verify the user journey

**Files:**
- Create: `backend/Dockerfile`
- Create: `backend/.dockerignore`
- Create: `frontend/admin/Dockerfile`
- Create: `frontend/.dockerignore`
- Create: `infrastructure/compose.yaml`
- Create: `infrastructure/.env.example`
- Create: `infrastructure/smoke-stage-1.sh`
- Create: `docs/local-development.md`
- Modify: `backend/src/main/resources/application.yml`
- Test: full backend, frontend, Compose health, and smoke workflow.

**Interfaces:**
- Consumes: backend jar and admin production build.
- Produces: `docker compose --env-file infrastructure/.env -f infrastructure/compose.yaml up --build`.
- Produces: healthy backend on `localhost:8080`, admin on `localhost:3000`, PostgreSQL on localhost only for development.

- [ ] **Step 1: Write the smoke script before container wiring**

The executable script must:

```bash
#!/usr/bin/env bash
set -euo pipefail

api_base=${PAYMENT_API_BASE_URL:-http://localhost:8080}
cookie_jar=$(mktemp)
trap 'rm -f "$cookie_jar"' EXIT

curl --fail --silent "$api_base/actuator/health/readiness" | jq -e '.status == "UP"' >/dev/null
curl --fail --silent --cookie-jar "$cookie_jar" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${PAYMENT_ADMIN_BOOTSTRAP_USERNAME}\",\"password\":\"${PAYMENT_ADMIN_BOOTSTRAP_PASSWORD}\"}" \
  "$api_base/api/admin/v1/auth/sessions" >/dev/null

xsrf=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$cookie_jar" | tail -1)
test -n "$xsrf"
curl --fail --silent --cookie "$cookie_jar" -H "X-XSRF-TOKEN: $xsrf" \
  "$api_base/api/admin/v1/sites" | jq -e 'type == "array"' >/dev/null
```

Extend it with API calls from Task 7 to create a unique fake account/site/product/price and publish the price; use a timestamp suffix so reruns do not collide. Assert the response contains `PUBLISHED` and a `fake_price_` reference.

- [ ] **Step 2: Run the smoke script and observe failure**

Run: `bash infrastructure/smoke-stage-1.sh`

Expected: connection failure because the Compose stack does not exist yet.

- [ ] **Step 3: Add production Dockerfiles**

Backend Dockerfile uses Maven 3.9.11 with Temurin 25 for the build stage and a non-root Temurin 25 JRE runtime. Frontend Dockerfile uses Node 24 Alpine with Corepack/pnpm 11.1.3 and Next.js standalone output. Its Docker build context is `frontend/`, not `frontend/admin/`, so it can copy the shared workspace file and lockfile before `admin/package.json`. Both images include health checks and copy only dependency manifests before source code for layer caching. The `.dockerignore` files exclude `target`, `.next`, `node_modules`, IDE files, and local environment files.

- [ ] **Step 4: Add Compose and environment template**

Compose services:

```yaml
services:
  postgres:
    image: postgres:18-alpine
    environment:
      POSTGRES_DB: payment
      POSTGRES_USER: payment
      POSTGRES_PASSWORD: ${PAYMENT_DB_PASSWORD:?set PAYMENT_DB_PASSWORD}
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U payment -d payment"]
      interval: 5s
      timeout: 3s
      retries: 20
    ports: ["127.0.0.1:5432:5432"]
    volumes: ["payment-postgres:/var/lib/postgresql/data"]

  backend:
    build: ../backend
    environment:
      PAYMENT_DB_URL: jdbc:postgresql://postgres:5432/payment
      PAYMENT_DB_USERNAME: payment
      PAYMENT_DB_PASSWORD: ${PAYMENT_DB_PASSWORD}
      PAYMENT_PROVIDER: fake
      PAYMENT_SECURE_COOKIE: "false"
      PAYMENT_ADMIN_BOOTSTRAP_USERNAME: ${PAYMENT_ADMIN_BOOTSTRAP_USERNAME}
      PAYMENT_ADMIN_BOOTSTRAP_PASSWORD: ${PAYMENT_ADMIN_BOOTSTRAP_PASSWORD}
    depends_on:
      postgres: { condition: service_healthy }
    ports: ["8080:8080"]

  admin:
    build:
      context: ../frontend
      dockerfile: admin/Dockerfile
    environment:
      BACKEND_INTERNAL_URL: http://backend:8080
    depends_on:
      backend: { condition: service_healthy }
    ports: ["3000:3000"]

volumes:
  payment-postgres:
```

`.env.example` contains `PAYMENT_DB_PASSWORD=local-payment-password`, `PAYMENT_ADMIN_BOOTSTRAP_USERNAME=admin`, and `PAYMENT_ADMIN_BOOTSTRAP_PASSWORD=change-this-local-password`. The documentation instructs the user to copy the file to untracked `infrastructure/.env` and change both passwords before any non-local deployment.

- [ ] **Step 5: Start the stack and run the smoke workflow**

Run: `cp infrastructure/.env.example infrastructure/.env`

Run: `docker compose --env-file infrastructure/.env -f infrastructure/compose.yaml up --build -d`

Run: `set -a; source infrastructure/.env; set +a; bash infrastructure/smoke-stage-1.sh`

Expected: script exits 0 after creating and publishing a fake-provider price.

- [ ] **Step 6: Verify admin UI manually and capture exact results in docs**

Open `http://localhost:3000`, login with local environment credentials, create a second Site, add a Product, create monthly Price version 1, publish it, then create version 2 with a different amount. Confirm version 1 remains published and version 2 remains DRAFT until explicit publication policy is handled in a later command.

Document ports, commands, environment variables, fake-provider behavior, database reset command scoped to the named `payment-postgres` volume, and the fact that Stripe, SDK checkout, customers, donations, and subscriptions arrive in later stages.

- [ ] **Step 7: Run all automated verification**

Run: `cd backend && ./mvnw test`

Run: `cd frontend && pnpm --filter admin lint && pnpm --filter admin typecheck && pnpm --filter admin test --run && pnpm --filter admin build`

Run: `docker compose --env-file infrastructure/.env -f infrastructure/compose.yaml ps`

Expected: backend BUILD SUCCESS, frontend commands PASS, and all three Compose services are healthy/running.

- [ ] **Step 8: Local completion checkpoint**

Record the command outputs in the implementation handoff. Do not run `git init`, `git add`, `git commit`, or `git push`.

Stage 1 is complete only when the API smoke workflow, Cucumber scenarios, backend suite, frontend suite, production builds, and Compose health checks all pass from a clean local checkout directory.
