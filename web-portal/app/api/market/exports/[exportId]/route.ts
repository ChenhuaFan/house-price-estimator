import { NextRequest, NextResponse } from "next/server";
import { errorResponse, proxyRequest, requireSameOrigin, serviceUrl } from "@/lib/bff/proxy";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function target(exportId: string): URL | null {
  return UUID.test(exportId)
    ? serviceUrl("market", `/api/v2/market/exports/${exportId}`)
    : null;
}

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ exportId: string }> },
): Promise<NextResponse> {
  const url = target((await params).exportId);
  return url ? proxyRequest(request, url) : errorResponse(400, "INVALID_EXPORT_ID", "Export ID must be a valid UUID.");
}

export async function DELETE(
  request: NextRequest,
  { params }: { params: Promise<{ exportId: string }> },
): Promise<NextResponse> {
  const rejected = requireSameOrigin(request);
  if (rejected) return rejected;
  const url = target((await params).exportId);
  return url ? proxyRequest(request, url) : errorResponse(400, "INVALID_EXPORT_ID", "Export ID must be a valid UUID.");
}
