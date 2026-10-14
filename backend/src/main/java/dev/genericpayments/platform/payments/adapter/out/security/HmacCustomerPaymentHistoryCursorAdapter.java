package dev.genericpayments.platform.payments.adapter.out.security;

import dev.genericpayments.platform.payments.application.port.out.CustomerPaymentHistoryCursorPort;
import dev.genericpayments.platform.payments.domain.PaymentId;
import dev.genericpayments.platform.sites.domain.SiteId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacCustomerPaymentHistoryCursorAdapter implements CustomerPaymentHistoryCursorPort {
    private static final String ENVELOPE_VERSION="c1";
    private static final int PAYLOAD_VERSION=1;
    private static final byte[] DOMAIN="generic-payments/customer-history/c1\0".getBytes(StandardCharsets.US_ASCII);
    private final CustomerPaymentHistoryCursorProperties properties;
    public HmacCustomerPaymentHistoryCursorAdapter(CustomerPaymentHistoryCursorProperties properties) {
        this.properties=properties;
    }
    @Override public String encode(Position position) {
        try {
            byte[] payload=serialize(position);
            String keyVersion=properties.activeKeyVersion();
            byte[] signature=authenticate(keyVersion,payload,properties.key(keyVersion));
            var encoder=Base64.getUrlEncoder().withoutPadding();
            return ENVELOPE_VERSION+"."+keyVersion+"."+encoder.encodeToString(payload)+"."+encoder.encodeToString(signature);
        } catch(GeneralSecurityException|java.io.IOException impossible) {
            throw new IllegalStateException("Customer history cursor encoding unavailable",impossible);
        }
    }
    @Override public Position decode(String cursor) {
        try {
            if(cursor==null||cursor.isBlank()||cursor.length()>4096) throw new IllegalArgumentException();
            String[] parts=cursor.split("\\.",-1);
            if(parts.length!=4||!ENVELOPE_VERSION.equals(parts[0])||!parts[1].matches("v[1-9][0-9]{0,8}")
                    ||parts[2].isEmpty()||parts[2].length()>512||parts[3].isEmpty()||parts[3].length()>128
                    ||parts[2].contains("=")||parts[3].contains("=")) throw new IllegalArgumentException();
            byte[] key=properties.key(parts[1]);
            if(key==null) throw new IllegalArgumentException();
            var decoder=Base64.getUrlDecoder();
            byte[] payload=decoder.decode(parts[2]);
            byte[] supplied=decoder.decode(parts[3]);
            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(payload).equals(parts[2])
                    ||!Base64.getUrlEncoder().withoutPadding().encodeToString(supplied).equals(parts[3])
                    ||supplied.length!=32||!MessageDigest.isEqual(authenticate(parts[1],payload,key),supplied))
                throw new IllegalArgumentException();
            return deserialize(payload);
        } catch(RuntimeException|GeneralSecurityException|java.io.IOException invalid) {
            throw invalid();
        }
    }
    private static byte[] serialize(Position position) throws java.io.IOException {
        byte[] shape=position.queryShape().getBytes(StandardCharsets.UTF_8);
        if(shape.length<1||shape.length>128) throw new IllegalArgumentException("Invalid Customer history cursor position");
        var bytes=new ByteArrayOutputStream(128);
        try(var output=new DataOutputStream(bytes)) {
            output.writeByte(PAYLOAD_VERSION);
            writeUuid(output,position.siteId().value());writeUuid(output,position.customerId());
            output.writeInt(shape.length);output.write(shape);output.writeInt(position.pageSize());
            output.writeLong(position.createdAt().getEpochSecond());output.writeInt(position.createdAt().getNano());
            writeUuid(output,position.paymentId().value());
        }
        return bytes.toByteArray();
    }
    private static Position deserialize(byte[] payload) throws java.io.IOException {
        if(payload.length<65||payload.length>197) throw new IllegalArgumentException();
        try(var input=new DataInputStream(new ByteArrayInputStream(payload))) {
            if(input.readUnsignedByte()!=PAYLOAD_VERSION) throw new IllegalArgumentException();
            UUID site=readUuid(input),customer=readUuid(input);
            int shapeSize=input.readInt();
            if(shapeSize<1||shapeSize>128||shapeSize>input.available()-32) throw new IllegalArgumentException();
            byte[] shape=input.readNBytes(shapeSize);
            if(shape.length!=shapeSize) throw new IllegalArgumentException();
            String decodedShape=new String(shape,StandardCharsets.UTF_8);
            if(!java.util.Arrays.equals(decodedShape.getBytes(StandardCharsets.UTF_8),shape)) throw new IllegalArgumentException();
            int pageSize=input.readInt();long seconds=input.readLong();int nanos=input.readInt();UUID payment=readUuid(input);
            if(input.available()!=0||nanos<0||nanos>999_999_999||nanos%1_000!=0) throw new IllegalArgumentException();
            return new Position(new SiteId(site),customer,decodedShape,pageSize,Instant.ofEpochSecond(seconds,nanos),new PaymentId(payment));
        }
    }
    private static byte[] authenticate(String keyVersion,byte[] payload,byte[] key) throws GeneralSecurityException {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
        mac.update(DOMAIN);mac.update(keyVersion.getBytes(StandardCharsets.US_ASCII));mac.update((byte)0);mac.update(payload);
        return mac.doFinal();
    }
    private static void writeUuid(DataOutputStream output,UUID value) throws java.io.IOException {
        output.writeLong(value.getMostSignificantBits());output.writeLong(value.getLeastSignificantBits());
    }
    private static UUID readUuid(DataInputStream input) throws java.io.IOException {
        return new UUID(input.readLong(),input.readLong());
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid Customer history cursor");}
    @Override public String toString(){return "HmacCustomerPaymentHistoryCursorAdapter[redacted]";}
}
