import { NextRequest, NextResponse } from "next/server";
import { copyAllowedQuery, proxyRequest, requireSameOrigin, serviceUrl } from "@/lib/bff/proxy";

export function GET(request: NextRequest): Promise<NextResponse> {
  const target = copyAllowedQuery(request.nextUrl.searchParams, serviceUrl("estimator", "/api/v1/estimates"));
  return proxyRequest(request, target, { includeClient: true });
}

export function POST(request: NextRequest): Promise<NextResponse> | NextResponse {
  const rejected = requireSameOrigin(request);
  if (rejected) return rejected;
  return proxyRequest(request, serviceUrl("estimator", "/api/v1/estimates"), {
    includeClient: true,
    jsonBody: true,
  });
}

export function DELETE(request: NextRequest): Promise<NextResponse> | NextResponse {
  const rejected = requireSameOrigin(request);
  if (rejected) return rejected;
  return proxyRequest(request, serviceUrl("estimator", "/api/v1/estimates"), { includeClient: true });
}
