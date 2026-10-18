"use client";

import type { HistoryPage } from "@/lib/api/types";
import { Comparison } from "@/features/estimator/comparison";
import { EstimateForm } from "@/features/estimator/estimate-form";
import { EstimateResult } from "@/features/estimator/estimate-result";
import { HistoryTable } from "@/features/estimator/history-table";
import { useEstimator } from "@/features/estimator/use-estimator";

export function EstimatorWorkspace({ initialHistory }: { initialHistory: HistoryPage }): React.ReactElement {
  const estimator = useEstimator(initialHistory);
  const busy = estimator.state.pending !== null;
  const compared = estimator.state.history.filter((item) => estimator.state.selectedIds.has(item.estimate_id));
  return (
    <div className="space-y-6">
      <div className="grid gap-6 xl:grid-cols-[minmax(0,1.6fr)_minmax(20rem,0.8fr)]">
        <EstimateForm busy={estimator.state.pending === "create"} apiError={estimator.state.error} onSubmit={estimator.estimate} />
        <EstimateResult estimate={estimator.state.current} />
      </div>
      <Comparison estimates={compared} />
      <HistoryTable
        estimates={estimator.state.history}
        selectedIds={estimator.state.selectedIds}
        nextCursor={estimator.state.nextCursor}
        busy={busy}
        onToggle={estimator.toggleSelected}
        onDelete={estimator.remove}
        onClear={estimator.clear}
        onLoadMore={estimator.loadMore}
      />
    </div>
  );
}
