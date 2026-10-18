import { NextRequest, NextResponse } from "next/server";
import { copyAllowedQuery, proxyRequest, requireSameOrigin, serviceUrl } from "@/lib/bff/proxy";

export function POST(request: NextRequest): Promise<NextResponse> | NextResponse {
  const rejected = requireSameOrigin(request);
  if (rejected) return rejected;
  const target = copyAllowedQuery(
    request.nextUrl.searchParams,
    serviceUrl("market", "/api/v1/market/exports/report.pdf"),
  );
  return proxyRequest(request, target, { jsonBody: true });
}
