package dev.genericpayments.platform.customers.adapter.out.mail;

import static org.assertj.core.api.Assertions.*;

import dev.genericpayments.platform.customers.application.port.out.CustomerMailDeliveryPort;
import dev.genericpayments.platform.customers.adapter.in.configuration.CustomerEmailOtpProperties;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import java.util.Map;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpCustomerMailAdapterTest {
    @Test void sendsConfiguredEnvelopeWithoutDatabaseTransaction() {
        RecordingMailSender sender=new RecordingMailSender();
        var adapter=new SmtpCustomerMailAdapter(sender,
            new CustomerEmailOtpProperties.Smtp("smtp.example.test",587,"user","secret","test-default",
                "no-reply@example.test",true,false,2000,5000,5000));
        adapter.deliver(new CustomerMailDeliveryPort.Message("test-default","person@example.test","Sign in","Code 123456"));
        assertThat(sender.sent).isNotNull();
        assertThat(sender.sent.getFrom()).isEqualTo("no-reply@example.test");
        assertThat(sender.sent.getTo()).containsExactly("person@example.test");
        assertThat(sender.sent.getSubject()).isEqualTo("Sign in");
        assertThat(sender.sent.getText()).isEqualTo("Code 123456");
    }

    @Test void authenticationFailureIsClassifiedAsConfigurationWithoutLeakingItsMessage() {
        RecordingMailSender sender=new RecordingMailSender();
        sender.failure=new MailAuthenticationException("person@example.test 123456");
        var adapter=new SmtpCustomerMailAdapter(sender,
            new CustomerEmailOtpProperties.Smtp("smtp.example.test",587,"user","secret","test-default",
                "no-reply@example.test",true,false,2000,5000,5000));
        assertThatThrownBy(() -> adapter.deliver(new CustomerMailDeliveryPort.Message("test-default","person@example.test","Sign in","Code 123456")))
            .isInstanceOfSatisfying(CustomerMailDeliveryPort.DeliveryException.class,error -> {
                assertThat(error.category()).isEqualTo(CustomerMailDeliveryPort.FailureCategory.CONFIGURATION);
                assertThat(error.getMessage()).doesNotContain("person@example.test","123456");
            });
    }

    @Test void parseAndTransportFailuresHaveBoundedClassificationsWithoutRawMessages() {
        assertFailure(new MailParseException("person@example.test 123456"),CustomerMailDeliveryPort.FailureCategory.PERMANENT);
        assertFailure(new MailSendException("person@example.test 123456"),CustomerMailDeliveryPort.FailureCategory.TRANSIENT);
    }

    @Test void nestedSmtpFourHundredsAreTransientButFiveHundredsAndInvalidRecipientsArePermanent() throws Exception {
        var recipient=new InternetAddress("person@example.test");
        assertFailure(new MailSendException(Map.of(new SimpleMailMessage(),
            new SMTPAddressFailedException(recipient,"RCPT TO",421,"unsafe transient detail"))),
            CustomerMailDeliveryPort.FailureCategory.TRANSIENT);
        assertFailure(new MailSendException(Map.of(new SimpleMailMessage(),
            new SendFailedException("generic wrapper",
                new SMTPAddressFailedException(recipient,"RCPT TO",450,"unsafe nested transient detail")))),
            CustomerMailDeliveryPort.FailureCategory.TRANSIENT);
        assertFailure(new MailSendException(Map.of(new SimpleMailMessage(),
            new SMTPAddressFailedException(recipient,"RCPT TO",550,"unsafe permanent detail"))),
            CustomerMailDeliveryPort.FailureCategory.PERMANENT);
        assertFailure(new MailSendException(Map.of(new SimpleMailMessage(),
            new SendFailedException("unsafe invalid recipient",null,null,null,new jakarta.mail.Address[]{recipient}))),
            CustomerMailDeliveryPort.FailureCategory.PERMANENT);
    }

    @Test void nestedAuthenticationFailureIsConfigurationAndPermanentCauseWinsOverGenericWrapper() {
        assertFailure(new MailSendException(Map.of(new SimpleMailMessage(),
            new AuthenticationFailedException("unsafe credentials"))),CustomerMailDeliveryPort.FailureCategory.CONFIGURATION);
        assertFailure(new MailSendException("generic wrapper",
            new MailParseException("unsafe permanent cause")),CustomerMailDeliveryPort.FailureCategory.PERMANENT);
    }

    private static void assertFailure(MailException failure,CustomerMailDeliveryPort.FailureCategory expected) {
        RecordingMailSender sender=new RecordingMailSender(); sender.failure=failure;
        var adapter=new SmtpCustomerMailAdapter(sender,
            new CustomerEmailOtpProperties.Smtp("smtp.example.test",587,"user","secret","test-default",
                "no-reply@example.test",true,false,2000,5000,5000));
        assertThatThrownBy(() -> adapter.deliver(new CustomerMailDeliveryPort.Message(
                "test-default","person@example.test","Sign in","Code 123456")))
            .isInstanceOfSatisfying(CustomerMailDeliveryPort.DeliveryException.class,error -> {
                assertThat(error.category()).isEqualTo(expected);
                assertThat(error.getMessage()).doesNotContain("person@example.test","123456");
            });
    }

    private static final class RecordingMailSender extends JavaMailSenderImpl {
        private SimpleMailMessage sent;
        private MailException failure;

        @Override public void send(SimpleMailMessage message) throws MailException {
            if(failure!=null) throw failure;
            sent=new SimpleMailMessage(message);
        }
    }
}
