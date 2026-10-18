/** Server-only loader for the estimator's initial, visitor-scoped history. */
import { headers } from "next/headers";
import type { HistoryPage } from "@/lib/api/types";
import { fetchJson } from "@/lib/server/request";

const EMPTY_HISTORY: HistoryPage = { items: [], next_cursor: null };

export interface EstimatorPageModel {
  history: HistoryPage;
  warning: string | null;
}

/** Returns history for the first render and degrades to an actionable warning on failure. */
export async function getEstimatorPageModel(): Promise<EstimatorPageModel> {
  const requestHeaders = await headers();
  const clientId = requestHeaders.get("x-estimator-client-id");
  const target = new URL(
    "/api/v1/estimates?limit=20",
    process.env.ESTIMATOR_SERVICE_URL ?? "http://localhost:8001",
  );

  try {
    const history = await fetchJson<HistoryPage>(target, {
      headers: clientId ? { "X-Estimator-Client-ID": clientId } : undefined,
    });
    return { history, warning: null };
  } catch {
    return {
      history: EMPTY_HISTORY,
      warning: "Estimate history is temporarily unavailable. You can still create a new estimate.",
    };
  }
}
