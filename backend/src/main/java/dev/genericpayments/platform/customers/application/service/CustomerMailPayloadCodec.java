package dev.genericpayments.platform.customers.application.service;

import dev.genericpayments.platform.customers.application.port.out.CustomerMailDeliveryPort;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Internal length-safe envelope. The whole encoded value is encrypted before persistence. */
final class CustomerMailPayloadCodec {
    String encode(CustomerMailDeliveryPort.Message message) {
        return encode(message.senderRef())+"."+encode(message.recipient())+"."+encode(message.subject())+"."+encode(message.body());
    }
    CustomerMailDeliveryPort.Message decode(String value) {
        try {
            String[] fields=value.split("\\.",-1);
            if(fields.length!=4) throw new IllegalArgumentException();
            var message=new CustomerMailDeliveryPort.Message(decodeField(fields[0]),decodeField(fields[1]),decodeField(fields[2]),decodeField(fields[3]));
            if(message.senderRef().isBlank()||message.recipient().isBlank()||message.subject().isBlank()||message.body().isBlank()
                ||message.recipient().length()>254||message.subject().length()>998||message.body().length()>20_000) throw new IllegalArgumentException();
            return message;
        } catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid encrypted Customer mail payload");}
    }
    private static String encode(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    private static String decodeField(String value){return new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);}
}
