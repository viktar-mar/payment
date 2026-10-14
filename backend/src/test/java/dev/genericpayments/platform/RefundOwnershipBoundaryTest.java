package dev.genericpayments.platform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.genericpayments.platform.payments.application.port.out.LoadRefundOwnershipPort;
import dev.genericpayments.platform.provider.adapter.out.fake.FakeProviderStatePersistenceAdapter;
import org.junit.jupiter.api.Test;

class RefundOwnershipBoundaryTest {
    @Test void refundOwnershipCrossesOnlyAFrameworkFreeApplicationPort() {
        var types=new ClassFileImporter().importClasses(LoadRefundOwnershipPort.class,
            LoadRefundOwnershipPort.RefundOwnership.class,FakeProviderStatePersistenceAdapter.class);
        noClasses().that().resideInAPackage("..payments.application.port.out..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..","jakarta.persistence..",
                "java.sql..","javax.sql..","org.postgresql..","..adapter..")
            .check(types);
        classes().that().haveFullyQualifiedName(FakeProviderStatePersistenceAdapter.class.getName())
            .should().dependOnClassesThat().haveFullyQualifiedName(LoadRefundOwnershipPort.class.getName()).check(types);
        noClasses().that().resideInAPackage("..provider.adapter..")
            .should().dependOnClassesThat().resideInAPackage("..payments.adapter..").check(types);
    }
}
