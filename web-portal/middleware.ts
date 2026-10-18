/** Issues the opaque visitor identity used to isolate estimator history. */

import { NextRequest, NextResponse } from "next/server";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/** Preserves a valid visitor cookie or creates one for the downstream request. */
export function middleware(request: NextRequest): NextResponse {
  const existing = request.cookies.get("hsbc_estimator_client")?.value;
  if (existing && UUID_V4.test(existing)) return NextResponse.next();

  const clientId = crypto.randomUUID();
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-estimator-client-id", clientId);

  const response = NextResponse.next({ request: { headers: requestHeaders } });
  const publicOrigin = process.env.PORTAL_PUBLIC_ORIGIN ?? request.nextUrl.origin;
  response.cookies.set({
    name: "hsbc_estimator_client",
    value: clientId,
    httpOnly: true,
    sameSite: "lax",
    secure: new URL(publicOrigin).protocol === "https:",
    path: "/",
    maxAge: 31_536_000,
    expires: new Date(Date.now() + 31_536_000_000),
  });
  return response;
}

export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico).*)"],
};
