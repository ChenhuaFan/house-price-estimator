import { NextRequest, NextResponse } from "next/server";
import { errorResponse, proxyRequest, requireSameOrigin, serviceUrl } from "@/lib/bff/proxy";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function POST(request: NextRequest): Promise<NextResponse> | NextResponse {
  const rejected = requireSameOrigin(request);
  if (rejected) return rejected;
  const idempotencyKey = request.headers.get("idempotency-key");
  if (!idempotencyKey || !UUID.test(idempotencyKey)) {
    return errorResponse(400, "INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be a valid UUID.");
  }
  return proxyRequest(request, serviceUrl("market", "/api/v2/market/exports"), {
    jsonBody: true,
    forwardHeaders: ["Idempotency-Key"],
  });
}
