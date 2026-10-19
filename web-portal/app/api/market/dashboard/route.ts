import { NextRequest, NextResponse } from "next/server";
import { getMarketPageModel } from "@/features/market/server";
import { errorResponse } from "@/lib/bff/proxy";
import { ServerDataError } from "@/lib/server/request";

/** Returns a complete version-consistent dashboard model for client-side updates after first paint. */
export async function GET(request: NextRequest): Promise<NextResponse> {
  try {
    const model = await getMarketPageModel(Object.fromEntries(request.nextUrl.searchParams));
    return NextResponse.json(model, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    if (error instanceof ServerDataError) {
      return errorResponse(error.status, error.code, error.message, error.correlationId);
    }
    return errorResponse(500, "INTERNAL_ERROR", "The dashboard could not be updated. Please retry.");
  }
}
