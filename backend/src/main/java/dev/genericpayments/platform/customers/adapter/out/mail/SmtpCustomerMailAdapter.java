package dev.genericpayments.platform.customers.adapter.out.mail;

import dev.genericpayments.platform.customers.adapter.in.configuration.CustomerEmailOtpProperties;
import dev.genericpayments.platform.customers.application.port.out.CustomerMailDeliveryPort;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.mail.*;
import jakarta.mail.internet.AddressException;
import java.util.*;

public final class SmtpCustomerMailAdapter implements CustomerMailDeliveryPort {
    private final JavaMailSender sender; private final CustomerEmailOtpProperties.Smtp properties;
    public SmtpCustomerMailAdapter(JavaMailSender sender,CustomerEmailOtpProperties.Smtp properties){this.sender=sender;this.properties=properties;}
    @Override public void deliver(Message message) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new DeliveryException(FailureCategory.CONFIGURATION);
        if(message==null||!properties.senderRef().equals(message.senderRef())) throw new DeliveryException(FailureCategory.CONFIGURATION);
        try {
            var mail=new SimpleMailMessage(); mail.setFrom(properties.fromAddress()); mail.setTo(message.recipient());
            mail.setSubject(message.subject()); mail.setText(message.body()); sender.send(mail);
        } catch(MailException failure){throw new DeliveryException(classify(failure));}
    }
    private static FailureCategory classify(Throwable root) {
        boolean permanent=false;
        var queue=new ArrayDeque<Throwable>(); var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>()); queue.add(root);
        for(int visited=0;!queue.isEmpty()&&visited<64;visited++) {
            Throwable failure=queue.removeFirst();
            if(!seen.add(failure)) continue;
            if(failure instanceof MailAuthenticationException||failure instanceof AuthenticationFailedException)
                return FailureCategory.CONFIGURATION;
            boolean smtpStatus=isSmtpStatusFailure(failure);
            if(failure instanceof MailParseException||failure instanceof AddressException
                ||(failure instanceof SendFailedException send&&send.getInvalidAddresses()!=null&&send.getInvalidAddresses().length>0))
                permanent=true;
            if(smtpStatus&&smtpReturnCode(failure)>=500) permanent=true;
            if(failure instanceof MailSendException send)
                send.getFailedMessages().values().stream().filter(Objects::nonNull).forEach(queue::addLast);
            if(failure instanceof MessagingException messaging&&messaging.getNextException()!=null)
                queue.addLast(messaging.getNextException());
            if(failure.getCause()!=null) queue.addLast(failure.getCause());
        }
        return permanent?FailureCategory.PERMANENT:FailureCategory.TRANSIENT;
    }
    private static boolean isSmtpStatusFailure(Throwable failure) {
        String name=failure.getClass().getName();
        return name.equals("org.eclipse.angus.mail.smtp.SMTPAddressFailedException")
            ||name.equals("org.eclipse.angus.mail.smtp.SMTPSendFailedException");
    }
    private static int smtpReturnCode(Throwable failure) {
        try{return ((Number)failure.getClass().getMethod("getReturnCode").invoke(failure)).intValue();}
        catch(ReflectiveOperationException ignored){return 0;}
    }
}
