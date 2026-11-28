package dev.genericpayments.platform.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CurrentSchemaVersionsTest {
    @Test void explicitContractMatchesShippedMigrationVersions() throws Exception {
        var directory = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        try (var files = Files.list(directory)) {
            var versions = files.map(path -> path.getFileName().toString())
                .filter(name -> name.matches("V[0-9_.]+__.*\\.sql"))
                .map(name -> name.substring(1, name.indexOf("__")).replace('_', '.'))
                .sorted(Comparator.comparing(MigrationVersion::fromVersion))
                .toList();
            assertThat(CurrentSchemaVersions.EXPECTED).containsExactlyElementsOf(versions);
        }
    }
}
