import { NextRequest, NextResponse } from "next/server";
import { proxyRequest, requireSameOrigin, serviceUrl } from "@/lib/bff/proxy";

export function POST(request: NextRequest): Promise<NextResponse> | NextResponse {
  const rejected = requireSameOrigin(request);
  if (rejected) return rejected;
  return proxyRequest(request, serviceUrl("market", "/api/v2/market/what-if"), { jsonBody: true });
}
