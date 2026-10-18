import { NextRequest, NextResponse } from "next/server";
import { errorResponse, proxyRequest, serviceUrl } from "@/lib/bff/proxy";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ exportId: string }> },
): Promise<NextResponse> {
  const { exportId } = await params;
  if (!UUID.test(exportId)) return errorResponse(400, "INVALID_EXPORT_ID", "Export ID must be a valid UUID.");
  return proxyRequest(request, serviceUrl("market", `/api/v2/market/exports/${exportId}/content`));
}
