package dev.genericpayments.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.genericpayments.platform.fixtures.domain.ForbiddenDomainDependencyFixtures.DataSourceDomainFixture;
import dev.genericpayments.platform.fixtures.domain.ForbiddenDomainDependencyFixtures.HttpDomainFixture;
import dev.genericpayments.platform.fixtures.domain.ForbiddenDomainDependencyFixtures.JacksonDomainFixture;
import dev.genericpayments.platform.fixtures.domain.ForbiddenDomainDependencyFixtures.JdbcDomainFixture;
import dev.genericpayments.platform.fixtures.domain.ForbiddenDomainDependencyFixtures.PostgreSqlDomainFixture;
import dev.genericpayments.platform.fixtures.domain.ForbiddenDomainDependencyFixtures.SpringDomainFixture;
import dev.genericpayments.platform.fixtures.domain.ForbiddenDomainDependencyFixtures.JpaDomainFixture;
import dev.genericpayments.platform.fixtures.domain.ForbiddenDomainDependencyFixtures.JoseDomainFixture;
import dev.genericpayments.platform.fixtures.domain.ForbiddenDomainDependencyFixtures.JavaMailDomainFixture;
import dev.genericpayments.platform.fixtures.domain.ForbiddenDomainDependencyFixtures.EncryptionDomainFixture;
import org.junit.jupiter.api.Test;

class ArchitectureRulesRuleTest {
    @Test
    void rejects_spring_dependency_from_domain() {
        assertRejected(SpringDomainFixture.class, "org.springframework.context.ApplicationContext");
    }

    @Test
    void rejects_jpa_dependency_from_domain() {
        assertRejected(JpaDomainFixture.class, "jakarta.persistence.EntityManager");
    }

    @Test
    void rejects_jose_dependency_from_domain() {
        assertRejected(JoseDomainFixture.class, "com.nimbusds.jose.JWSObject");
    }

    @Test
    void rejects_java_mail_dependency_from_domain() {
        assertRejected(JavaMailDomainFixture.class, "jakarta.mail.Session");
    }

    @Test
    void rejects_jackson_3_dependency_from_domain() {
        assertRejected(JacksonDomainFixture.class, "tools.jackson.databind.json.JsonMapper");
    }

    @Test
    void rejects_jdk_http_dependency_from_domain() {
        assertRejected(HttpDomainFixture.class, "java.net.http.HttpClient");
    }

    @Test
    void rejects_jdbc_dependency_from_domain() {
        assertRejected(JdbcDomainFixture.class, "java.sql.Connection");
    }

    @Test
    void rejects_data_source_dependency_from_domain() {
        assertRejected(DataSourceDomainFixture.class, "javax.sql.DataSource");
    }

    @Test
    void rejects_postgresql_dependency_from_domain() {
        assertRejected(PostgreSqlDomainFixture.class, "org.postgresql.PGConnection");
    }

    private void assertRejected(Class<?> fixture, String forbiddenDependency) {
        assertThatThrownBy(() -> ArchitectureRulesTest.domain_is_framework_free.check(
            new ClassFileImporter().importClasses(fixture)))
            .hasMessageContaining(forbiddenDependency);
    }

    @Test
    void rejects_encryption_implementation_from_domain() {
        assertRejected(EncryptionDomainFixture.class, "javax.crypto.Cipher");
    }
}
