import { NextRequest, NextResponse } from "next/server";
// Cookie presence only improves navigation. The backend validates the session on every API call.
export function proxy(request: NextRequest) {
  if (!request.cookies.has("PAYMENT_ADMIN_SESSION"))
    return NextResponse.redirect(new URL("/login", request.url));
  return NextResponse.next();
}
export const config = { matcher: ["/sites/:path*", "/payments/:path*", "/customers/:path*", "/donations/:path*"] };
