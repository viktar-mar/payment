package dev.genericpayments.platform.provider.application.model;

/** Conservative documented Sandbox URL subset. No URL value is retained here. */
public final class StripePortalNavigationUrl {
    private StripePortalNavigationUrl() {}
    public static boolean valid(String value){
        return value!=null&&value.length()<=2048&&value.chars().noneMatch(Character::isWhitespace)
            &&value.matches("https://billing\\.stripe\\.com/p/session\\?secret=test_[A-Za-z0-9_-]+");
    }
}
