package dev.genericpayments.platform.customers.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.genericpayments.platform.customers.application.model.CustomerAuthenticationResult;
import dev.genericpayments.platform.customers.application.port.in.*;
import dev.genericpayments.platform.customers.domain.AuthenticationIntent;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

class CustomerAuthenticationLogSafetyTest extends AuthenticationServiceIntegrationSupport {
    @Autowired BeginOidcAuthenticationUseCase begin;
    @Autowired ExchangeOidcTokenUseCase exchange;
    @Autowired ExchangeSiteTokenUseCase siteExchange;

    @Test void oidcCommandResultAndFailureDiagnosticsNeverContainCredentialsOrIdentityFacts() {
        var root=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var captured=new ListAppender<ILoggingEvent>(); captured.start(); root.addAppender(captured);
        try {
            var started=begin.begin(new BeginOidcAuthenticationUseCase.Command(SITE_KEY,OIDC_PROVIDER,ORIGIN,AuthenticationIntent.LINK));
            String subject="log-sensitive-subject",jti="log-sensitive-jti",email="log-sensitive@example.test";
            String token="oidc:"+subject+":"+jti+":"+started.nonce()+":"+email;
            var command=new ExchangeOidcTokenUseCase.Command(started.attemptId(),SITE_KEY,OIDC_PROVIDER,ORIGIN,
                AuthenticationIntent.LINK,started.nonce(),token);
            CustomerAuthenticationResult.LinkProofIssued result=(CustomerAuthenticationResult.LinkProofIssued)exchange.exchange(command);
            LoggerFactory.getLogger(getClass()).info("auth records {} {} {}",started,command,result);
            var failedCommand=new ExchangeOidcTokenUseCase.Command(started.attemptId(),SITE_KEY,OIDC_PROVIDER,ORIGIN,
                AuthenticationIntent.LINK,"failed-sensitive-nonce","bad-signature");
            try { exchange.exchange(failedCommand); }
            catch(PaymentApplicationException failure) {
                LoggerFactory.getLogger(getClass()).info("failed auth records {} {}",failedCommand,failure);
            }
            String logs=captured.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",(a,b)->a+'\n'+b);
            assertThat(logs).doesNotContain(token,started.nonce(),subject,jti,email,result.proof(),"failed-sensitive-nonce","bad-signature");
            assertThat(jdbc.queryForList("select * from oidc_authentication_attempts").toString()).doesNotContain(started.nonce(),token,subject,jti,email);
            assertThat(jdbc.queryForList("select * from identity_link_proofs").toString()).doesNotContain(result.proof(),token,subject,jti,email);
        } finally { root.detachAppender(captured); captured.stop(); }
    }

    @Test void siteTokenCommandResultAndFailureDiagnosticsNeverContainCredentialsOrIdentityFacts() {
        var root=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var captured=new ListAppender<ILoggingEvent>(); captured.start(); root.addAppender(captured);
        try {
            String subject="site-log-sensitive-subject",jti="site-log-sensitive-jti";
            String token="site:"+subject+":"+jti;
            var command=new ExchangeSiteTokenUseCase.Command(SITE_KEY,SITE_PROVIDER,ORIGIN,AuthenticationIntent.LINK,token);
            CustomerAuthenticationResult.LinkProofIssued result=(CustomerAuthenticationResult.LinkProofIssued)siteExchange.exchange(command);
            LoggerFactory.getLogger(getClass()).info("site auth records {} {}",command,result);
            try { siteExchange.exchange(command); }
            catch(PaymentApplicationException failure) {
                LoggerFactory.getLogger(getClass()).info("failed site auth records {} {}",command,failure);
            }
            String logs=captured.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("",(a,b)->a+'\n'+b);
            assertThat(logs).doesNotContain(token,subject,jti,result.proof());
            assertThat(jdbc.queryForList("select * from consumed_site_token_jtis").toString()).doesNotContain(token,subject,jti);
            assertThat(jdbc.queryForList("select * from identity_link_proofs").toString()).doesNotContain(result.proof(),token,subject,jti);
        } finally { root.detachAppender(captured); captured.stop(); }
    }
}
