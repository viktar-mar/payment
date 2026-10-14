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
            "com.fasterxml.jackson..", "tools.jackson..", "java.net.http..",
            "java.sql..", "javax.sql..", "org.postgresql..", "com.stripe..",
            "com.nimbusds..", "org.jose4j..", "jakarta.mail..", "javax.mail..",
            "javax.crypto..", "org.bouncycastle..")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule customer_application_does_not_depend_on_customer_adapters = noClasses()
        .that().resideInAPackage("dev.genericpayments.platform.customers.application..")
        .should().dependOnClassesThat().resideInAPackage("dev.genericpayments.platform.customers.adapter..")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule subscription_application_does_not_depend_on_adapters = noClasses()
        .that().resideInAPackage("dev.genericpayments.platform.subscriptions.application..")
        .should().dependOnClassesThat().resideInAPackage("..adapter..")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule customer_management_services_are_framework_free = noClasses()
        .that().haveNameMatching("dev\\.genericpayments\\.platform\\.customers\\.application\\.service\\.(CustomerIdentityLinkService|PartnerCustomerIdentityLinkService|CustomerAnonymizationService|CustomerQueryService)")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..")
        .allowEmptyShould(false);

    @ArchTest
    static final ArchRule customer_payment_history_service_is_framework_free = noClasses()
        .that().haveFullyQualifiedName("dev.genericpayments.platform.payments.application.service.CustomerPaymentHistoryService")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..")
        .allowEmptyShould(false);
}
