import type { Estimate, PropertyInput } from "@/lib/api/types";
import type { PortalApiError } from "@/features/estimator/api";
import { Comparison } from "@/features/estimator/comparison";
import { EstimateForm } from "@/features/estimator/estimate-form";
import { EstimateResult } from "@/features/estimator/estimate-result";
import { HistoryTable } from "@/features/estimator/history-table";

interface EstimatorWorkspaceViewProps {
  history: Estimate[];
  current: Estimate | null;
  compared: Estimate[];
  selectedIds: Set<string>;
  nextCursor: string | null;
  busy: boolean;
  warning: string | null;
  error: PortalApiError | null;
  scenario: PropertyInput | null;
  announcement: string;
  onEstimate: EstimateFormProps["onSubmit"];
  onToggle: (id: string) => void;
  onView: (id: string) => void;
  onReuse: (id: string) => void;
  onDelete: (id: string) => Promise<void>;
  onClear: () => Promise<void>;
  onLoadMore: () => Promise<void>;
}

type EstimateFormProps = React.ComponentProps<typeof EstimateForm>;

export function EstimatorWorkspaceView(props: EstimatorWorkspaceViewProps): React.ReactElement {
  return (
    <div className="space-y-5">
      <p className="sr-only" role="status" aria-live="polite">{props.announcement}</p>
      <div className="grid gap-5 xl:grid-cols-[minmax(0,1.35fr)_minmax(22rem,0.8fr)]">
        <EstimateForm busy={props.busy} apiError={props.error} scenario={props.scenario} onSubmit={props.onEstimate} />
        <EstimateResult estimate={props.current} />
      </div>
      <Comparison estimates={props.compared} />
      <HistoryTable estimates={props.history} selectedIds={props.selectedIds} nextCursor={props.nextCursor} busy={props.busy} warning={props.warning} onToggle={props.onToggle} onView={props.onView} onReuse={props.onReuse} onDelete={props.onDelete} onClear={props.onClear} onLoadMore={props.onLoadMore} />
    </div>
  );
}
