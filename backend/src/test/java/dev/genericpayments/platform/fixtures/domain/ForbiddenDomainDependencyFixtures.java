package dev.genericpayments.platform.fixtures.domain;

import com.nimbusds.jose.JWSObject;
import jakarta.mail.Session;
import jakarta.persistence.EntityManager;
import java.net.http.HttpClient;
import java.sql.Connection;
import javax.crypto.Cipher;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

public final class ForbiddenDomainDependencyFixtures {
    private ForbiddenDomainDependencyFixtures() {}

    public static final class SpringDomainFixture {
        private ApplicationContext context;
    }

    public static final class JpaDomainFixture {
        private EntityManager entityManager;
    }

    public static final class JoseDomainFixture {
        private JWSObject token;
    }

    public static final class JavaMailDomainFixture {
        private Session session;
    }

    public static final class JacksonDomainFixture {
        private JsonMapper mapper;
    }

    public static final class HttpDomainFixture {
        private HttpClient client;
    }

    public static final class JdbcDomainFixture {
        private Connection connection;
    }

    public static final class DataSourceDomainFixture {
        private DataSource dataSource;
    }

    public static final class PostgreSqlDomainFixture {
        private PGConnection connection;
    }

    public static final class EncryptionDomainFixture {
        private Cipher cipher;
    }
}
