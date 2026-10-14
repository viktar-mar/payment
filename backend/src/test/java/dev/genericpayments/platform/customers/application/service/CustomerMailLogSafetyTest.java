package dev.genericpayments.platform.customers.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.genericpayments.platform.customers.adapter.in.configuration.CustomerEmailOtpProperties;
import dev.genericpayments.platform.customers.application.port.in.RequestEmailOtpUseCase;
import dev.genericpayments.platform.customers.application.port.in.VerifyEmailOtpUseCase;
import dev.genericpayments.platform.customers.application.port.out.CustomerMailDeliveryPort;
import dev.genericpayments.platform.customers.domain.AuthenticationIntent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class CustomerMailLogSafetyTest {
    @Test void otpMailAndSmtpDiagnosticsRedactEveryCredentialAndMessageField() {
        String email="mail-log-sensitive@example.test",code="839104",source="203.0.113.91";
        String password="smtp-log-sensitive-password",subject="Subject-log-sensitive",body="Body-log-sensitive "+code;
        var request=new RequestEmailOtpUseCase.Command("site-key","https://site.example.test",AuthenticationIntent.AUTHENTICATE,email,source);
        var verify=new VerifyEmailOtpUseCase.Command(UUID.randomUUID(),"site-key","https://site.example.test",code);
        var message=new CustomerMailDeliveryPort.Message("sender-ref",email,subject,body);
        var smtp=new CustomerEmailOtpProperties.Smtp("smtp.example.test",587,"smtp-user",password,"sender-ref",
            "from@example.test",true,false,2000,5000,5000);
        var failure=new CustomerMailDeliveryPort.DeliveryException(CustomerMailDeliveryPort.FailureCategory.TRANSIENT);
        var root=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var captured=new ListAppender<ILoggingEvent>(); captured.start(); root.addAppender(captured);
        try {
            LoggerFactory.getLogger(getClass()).info("OTP/mail diagnostics {} {} {} {} {}",request,verify,message,smtp,failure);
            String logs=captured.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",(left,right)->left+'\n'+right);
            assertThat(logs).doesNotContain(email,code,source,password,subject,body);
            assertThat(logs).contains("credentials=redacted","CustomerMailMessage[redacted]","Smtp[redacted]");
        } finally {
            root.detachAppender(captured); captured.stop();
        }
    }
}
