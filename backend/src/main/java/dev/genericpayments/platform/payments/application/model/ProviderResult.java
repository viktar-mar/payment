package dev.genericpayments.platform.payments.application.model;
import java.util.Objects;
/** Synchronous acknowledgements deliberately have no success variant. */
public sealed interface ProviderResult {
 enum Kind {ACCEPTED,PROCESSING,REQUIRES_ACTION,RECOVERABLE_DECLINE,TERMINAL_DECLINE,TEMPORARILY_UNAVAILABLE,UNKNOWN}
 enum Code {ACCEPTED,PROCESSING,ACTION_REQUIRED,DECLINED,TERMINAL_DECLINE,UNAVAILABLE,UNKNOWN}
 enum ActionKind {COMPLETE_VERIFICATION}
 record Action(ActionKind kind,String payload){
  public Action{Objects.requireNonNull(kind);if(payload==null||!payload.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Invalid safe action");}
  public String description(){return "Complete payment verification";}
 }
 Kind kind(); Code code(); String reference();
 record Accepted(String reference,boolean processing) implements ProviderResult {
  public Accepted{ProviderRequestRules.reference(reference);}
  public Kind kind(){return processing?Kind.PROCESSING:Kind.ACCEPTED;}
  public Code code(){return processing?Code.PROCESSING:Code.ACCEPTED;}
 }
 record RequiresAction(String reference,Action action) implements ProviderResult {
  public RequiresAction{ProviderRequestRules.reference(reference);Objects.requireNonNull(action);}
  public Kind kind(){return Kind.REQUIRES_ACTION;} public Code code(){return Code.ACTION_REQUIRED;}
 }
 record RecoverableDecline(String reference) implements ProviderResult {
  public RecoverableDecline{ProviderRequestRules.reference(reference);}
  public Kind kind(){return Kind.RECOVERABLE_DECLINE;} public Code code(){return Code.DECLINED;}
 }
 record TerminalDecline(String reference) implements ProviderResult {
  public TerminalDecline{ProviderRequestRules.reference(reference);}
  public Kind kind(){return Kind.TERMINAL_DECLINE;} public Code code(){return Code.TERMINAL_DECLINE;}
 }
 record TemporarilyUnavailable() implements ProviderResult {
  public Kind kind(){return Kind.TEMPORARILY_UNAVAILABLE;} public Code code(){return Code.UNAVAILABLE;}public String reference(){return null;}
 }
 record Unknown(String reference) implements ProviderResult {
  public Unknown{if(reference!=null)ProviderRequestRules.reference(reference);}
  public Kind kind(){return Kind.UNKNOWN;} public Code code(){return Code.UNKNOWN;}
 }
}
