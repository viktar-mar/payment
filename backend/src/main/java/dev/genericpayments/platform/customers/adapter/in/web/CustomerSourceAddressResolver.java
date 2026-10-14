package dev.genericpayments.platform.customers.adapter.in.web;

import dev.genericpayments.platform.customers.adapter.in.configuration.CustomerEmailOtpProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public final class CustomerSourceAddressResolver {
    private final List<Cidr> trusted;
    public CustomerSourceAddressResolver(CustomerEmailOtpProperties properties) {
        try { trusted=properties.trustedProxyCidrs().stream().map(Cidr::parse).toList(); }
        catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid trusted proxy configuration");}
    }
    public String resolve(HttpServletRequest request) {
        InetAddress peer=parse(request.getRemoteAddr());
        if(trusted.isEmpty()||trusted.stream().noneMatch(cidr->cidr.contains(peer))) return peer.getHostAddress();
        List<String> values=Collections.list(request.getHeaders("X-Forwarded-For"));
        if(values.size()!=1) return peer.getHostAddress();
        String[] raw=values.getFirst().split(",",-1);
        if(raw.length==0||raw.length>20) return peer.getHostAddress();
        List<InetAddress> chain=new ArrayList<>();
        try { for(String value:raw) chain.add(parse(value.trim())); }
        catch(RuntimeException malformed){return peer.getHostAddress();}
        chain.add(peer);
        for(int index=chain.size()-1;index>=0;index--) {
            InetAddress candidate=chain.get(index);
            if(trusted.stream().noneMatch(cidr->cidr.contains(candidate))) return candidate.getHostAddress();
        }
        return chain.getFirst().getHostAddress();
    }
    private static InetAddress parse(String value) {
        if(value==null||value.isBlank()||value.length()>64||!(value.indexOf(':')>=0||value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")))
            throw new IllegalArgumentException("Invalid address");
        try{return InetAddress.getByName(value);}
        catch(UnknownHostException failure){throw new IllegalArgumentException("Invalid address");}
    }
    private record Cidr(byte[] network,int prefix) {
        static Cidr parse(String value) {
            if(value==null) throw new IllegalArgumentException();
            String[] parts=value.trim().split("/",-1); if(parts.length!=2) throw new IllegalArgumentException();
            byte[] bytes=CustomerSourceAddressResolver.parse(parts[0]).getAddress(); int prefix=Integer.parseInt(parts[1]);
            if(prefix<0||prefix>bytes.length*8) throw new IllegalArgumentException();
            byte[] network=bytes.clone();
            for(int bit=prefix;bit<network.length*8;bit++) network[bit/8]&=(byte)~(1<<(7-bit%8));
            return new Cidr(network,prefix);
        }
        boolean contains(InetAddress address) {
            byte[] candidate=address.getAddress(); if(candidate.length!=network.length)return false;
            int whole=prefix/8,bits=prefix%8;
            for(int i=0;i<whole;i++)if(candidate[i]!=network[i])return false;
            if(bits==0)return true; int mask=0xff<<(8-bits); return (candidate[whole]&mask)==(network[whole]&mask);
        }
    }
}
