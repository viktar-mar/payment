package dev.genericpayments.platform.payments.application.model;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
/** Deterministic flat JSON encoding of already normalized technical facts. */
public final class SafeFactJson {
    private SafeFactJson() {}
    public static void text(String value,int bound) {
        if(value==null||value.isBlank()||value.length()>bound||value.codePoints().anyMatch(Character::isISOControl))throw invalid();
        try { StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)); }
        catch(CharacterCodingException malformed){throw invalid();}
    }
    public static String json(Map<String,?> facts) {
        if(facts==null||facts.size()>20)throw invalid();var sorted=new TreeMap<String,Object>();sorted.putAll(facts);
        var out=new StringBuilder("{");boolean first=true;
        for(var e:sorted.entrySet()) {
            text(e.getKey(),64);if(!first)out.append(',');first=false;out.append(quote(e.getKey())).append(':');
            Object v=e.getValue();if(v==null)out.append("null");else if(v instanceof String s){if(!s.isEmpty())text(s,2048);out.append(quote(s));}
            else if(v instanceof Integer||v instanceof Long||v instanceof Boolean)out.append(v);else throw invalid();
        }
        out.append('}');if(out.toString().getBytes(StandardCharsets.UTF_8).length>4096)throw invalid();return out.toString();
    }
    private static String quote(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    public static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid normalized provider facts");}
}
