"use client";

import { Download, FileClock, X } from "lucide-react";
import { AnimatePresence, m } from "motion/react";
import { Button, Panel, buttonClassName } from "@/components/ui/primitives";
import type { ExportFormat, ExportJob } from "@/lib/api/types";
import { formatNumber, formatTimestamp } from "@/lib/format";

const active = new Set(["QUEUED", "RUNNING"]);

export function ExportCentre({
  jobs,
  creatingFormat,
  onCreate,
  onCancel,
  onDismiss,
}: {
  jobs: ExportJob[];
  creatingFormat: ExportFormat | null;
  onCreate: (format: ExportFormat) => Promise<void>;
  onCancel: (id: string) => Promise<void>;
  onDismiss: (id: string) => void;
}): React.ReactElement {
  return (
    <Panel className="p-4 sm:p-5" aria-labelledby="export-centre-heading">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div><h2 id="export-centre-heading" className="text-lg font-extrabold text-ink">Export centre</h2><p className="mt-1 text-xs text-ink-muted">Exports run in the background; the dashboard stays interactive.</p></div>
        <div className="flex gap-2"><Button type="button" variant="secondary" disabled={creatingFormat !== null} onClick={() => onCreate("CSV")}><Download className="size-4" aria-hidden="true" />{creatingFormat === "CSV" ? "Queuing…" : "Export CSV"}</Button><Button type="button" disabled={creatingFormat !== null} onClick={() => onCreate("PDF")}><FileClock className="size-4" aria-hidden="true" />{creatingFormat === "PDF" ? "Queuing…" : "Export PDF"}</Button></div>
      </div>
      {jobs.length === 0 ? <p className="mt-4 rounded-lg border border-dashed border-slate-300 bg-slate-50 p-5 text-sm text-ink-muted">No export jobs in this browser session.</p> : (
        <ul className="mt-4 space-y-2" aria-live="polite">
          <AnimatePresence initial={false}>{jobs.map((job) => <m.li layout initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} key={job.export_id} className="flex flex-col gap-3 rounded-lg border border-border-ui bg-slate-50 p-3 sm:flex-row sm:items-center">
            <span className={`size-2.5 shrink-0 rounded-full ${job.status === "SUCCEEDED" ? "bg-green" : job.status === "FAILED" || job.status === "EXPIRED" ? "bg-danger" : "bg-orange"}`} aria-hidden="true" />
            <div className="min-w-0 flex-1"><p className="font-bold text-ink">{job.format} · {job.status.toLowerCase()}</p><p className="mt-0.5 text-xs text-ink-muted">Created {formatTimestamp(job.created_at)}{job.row_count !== undefined ? ` · ${formatNumber(job.row_count)} rows` : ""}{job.content_length !== undefined ? ` · ${formatNumber(job.content_length)} bytes` : ""}</p>{job.details_truncated ? <p className="mt-1 text-xs font-semibold text-amber-900">PDF details were limited; summary metrics cover the full selection.</p> : null}</div>
            <div className="flex flex-wrap gap-2">{job.status === "SUCCEEDED" ? <a className={buttonClassName("secondary")} href={`/api/market/exports/${job.export_id}/content`} download><Download className="size-4" aria-hidden="true" />Download</a> : null}{active.has(job.status) ? <Button type="button" variant="danger" onClick={() => onCancel(job.export_id)}>Cancel</Button> : <Button type="button" variant="quiet" aria-label={`Dismiss ${job.format} export`} onClick={() => onDismiss(job.export_id)}><X className="size-4" aria-hidden="true" />Dismiss</Button>}</div>
          </m.li>)}</AnimatePresence>
        </ul>
      )}
    </Panel>
  );
}
