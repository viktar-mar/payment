package dev.genericpayments.platform.admin.application.port.in;
/** Revalidate captured authentication under the caller's transaction; never refresh or expose secrets. */
public interface RevalidateAdminUseCase {void revalidate(AuthenticatedAdmin admin);}
