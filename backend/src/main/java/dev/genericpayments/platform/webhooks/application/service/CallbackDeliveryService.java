package dev.genericpayments.platform.webhooks.application.service;
import dev.genericpayments.platform.webhooks.application.port.out.*;
import dev.genericpayments.platform.shared.application.PaymentApplicationException;
import dev.genericpayments.platform.shared.application.port.out.EncryptedSecretPort;
import java.time.Clock;
import java.util.*;
public final class CallbackDeliveryService {
    private final OutboxPort outbox;private final EncryptedSecretPort encryption;private final CallbackPayloadPort payload;private final CallbackHttpPort http;private final Clock clock;
    public CallbackDeliveryService(OutboxPort outbox,EncryptedSecretPort encryption,CallbackPayloadPort payload,CallbackHttpPort http,Clock clock){this.outbox=outbox;this.encryption=encryption;this.payload=payload;this.http=http;this.clock=clock;}
    public void requireOutsideTransaction(){if(outbox.ambientTransaction())throw new PaymentApplicationException("CALLBACK_DELIVERY_FAILED",409,"Callback requires an independent network boundary");}
    public void deliver(OutboxPort.Claim claim) {
        requireOutsideTransaction();CallbackHttpPort.Result result;byte[] body;
        try{body=payload.bytes(claim);}catch(RuntimeException invalid){outbox.complete(claim,CallbackHttpPort.Result.failed(null,CallbackHttpPort.ErrorCategory.PAYLOAD),clock.instant());return;}
        byte[] secret;
        try{secret=Base64.getUrlDecoder().decode(encryption.decrypt(claim.secret()));if(secret.length!=32)throw new IllegalArgumentException("Invalid callback secret");}
        catch(RuntimeException invalid){outbox.complete(claim,CallbackHttpPort.Result.failed(null,CallbackHttpPort.ErrorCategory.SECRET),clock.instant());return;}
        try{
            long timestamp=clock.instant().getEpochSecond();String signature=new CallbackSigner().sign(secret,timestamp,body);
            var headers=Map.of("Payment-Event-Id",claim.eventId().toString(),"Payment-Timestamp",Long.toString(timestamp),"Payment-Signature","v1="+signature,"Payment-Secret-Version",Integer.toString(claim.secretVersion()),"Content-Type","application/json");
            result=http.post(claim.endpoint(),headers,body);
        }catch(RuntimeException failure){result=CallbackHttpPort.Result.failed(null,CallbackHttpPort.ErrorCategory.NETWORK);}
        finally{Arrays.fill(secret,(byte)0);}
        outbox.complete(claim,result,clock.instant());
    }
}
