package dev.genericpayments.platform.donations;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "dev.genericpayments.platform", importOptions = ImportOption.DoNotIncludeTests.class)
class DonationQueryArchitectureTest {
    @ArchTest static final ArchRule donations_never_use_payment_persistence = noClasses()
        .that().resideInAPackage("..donations..")
        .should().dependOnClassesThat().resideInAPackage("..payments.adapter.out.persistence..");
    @ArchTest static final ArchRule customer_adapters_never_use_payment_persistence = noClasses()
        .that().resideInAPackage("..customers.adapter.in..")
        .should().dependOnClassesThat().resideInAPackage("..payments.adapter.out.persistence..");
    @ArchTest static final ArchRule payment_application_never_uses_donation_services_or_persistence = noClasses()
        .that().resideInAPackage("..payments.application..")
        .should().dependOnClassesThat().resideInAnyPackage("..donations.application.service..", "..donations.adapter..");
    @ArchTest static final ArchRule donation_queries_never_receive_financial_aggregates = noClasses()
        .that().haveNameMatching(".*(DonationQueryService|DonationPaymentQueryPort|DonationPaymentQueryAdapter|CustomerDonationHistoryService)")
        .should().dependOnClassesThat().haveFullyQualifiedName("dev.genericpayments.platform.payments.domain.Payment");
}
