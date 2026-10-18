import { NextRequest, NextResponse } from "next/server";
import { copyAllowedQuery, proxyRequest, serviceUrl } from "@/lib/bff/proxy";

export function GET(request: NextRequest): Promise<NextResponse> {
  const target = copyAllowedQuery(request.nextUrl.searchParams, serviceUrl("market", "/api/v1/market/analysis"));
  return proxyRequest(request, target);
}
