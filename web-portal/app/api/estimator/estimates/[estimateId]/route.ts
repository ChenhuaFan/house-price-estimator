import { NextRequest, NextResponse } from "next/server";
import { errorResponse, proxyRequest, requireSameOrigin, serviceUrl } from "@/lib/bff/proxy";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export async function DELETE(
  request: NextRequest,
  context: { params: Promise<{ estimateId: string }> },
): Promise<NextResponse> {
  const rejected = requireSameOrigin(request);
  if (rejected) return rejected;
  const { estimateId } = await context.params;
  if (!UUID.test(estimateId)) {
    return errorResponse(400, "INVALID_ESTIMATE_ID", "Estimate identifier is invalid.");
  }
  return proxyRequest(
    request,
    serviceUrl("estimator", `/api/v1/estimates/${estimateId}`),
    { includeClient: true },
  );
}
