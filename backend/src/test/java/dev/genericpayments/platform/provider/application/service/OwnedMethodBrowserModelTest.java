package dev.genericpayments.platform.provider.application.service;

import dev.genericpayments.platform.provider.application.model.OwnedMethodBrowser.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OwnedMethodBrowserModelTest {
    private final Instant now=Instant.parse("2026-11-07T12:00:00Z");
    @Test void rootAndSelectionRequireExactBoundedIntervalsAndSafeDigests() {
        var id=UUID.randomUUID();
        assertThatCode(()->new Root(id,id,id,id,2,"seti_original","v1","a".repeat(64),now,now.plusSeconds(600))).doesNotThrowAnyException();
        assertThatThrownBy(()->new Root(id,id,id,id,2,"seti_original","v1","a".repeat(64),now,now.plusSeconds(601))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Root(id,id,id,id,1,"seti_original","v1","a".repeat(64),now,now.plusSeconds(600))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Selection(id,"raw-key",now,now.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Selection(id,"a".repeat(64),now,now.plusSeconds(31))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->new Selection(id,"a".repeat(64),now,now.plusSeconds(10))).doesNotThrowAnyException();
    }
    @Test void enrollmentRejectsMissingAndMalformedOriginalIdentity() {
        assertThatThrownBy(()->new Enrollment(null,"https://test.example",now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new Enrollment(UUID.randomUUID(),"https://test.example\n",now)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void receiptDescriptionsAreRedacted() {
        var id=UUID.randomUUID();var digest="a".repeat(64);
        assertThat(new Enrollment(id,"https://test.example",now).toString()).doesNotContain(id.toString(),"https://");
        assertThat(new Root(id,id,id,id,2,"seti_original","v1",digest,now,now.plusSeconds(600)).toString()).doesNotContain("seti_",digest,id.toString());
        assertThat(new Selection(id,digest,now,now.plusSeconds(30)).toString()).doesNotContain(digest,id.toString());
    }
}
