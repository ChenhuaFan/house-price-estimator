import { AlertCircle, CheckCircle2, Info, TriangleAlert } from "lucide-react";

interface StatusMessageProps {
  tone?: "info" | "success" | "warning" | "error";
  title: string;
  children?: React.ReactNode;
  live?: boolean;
}

export function StatusMessage({ tone = "info", title, children, live = false }: StatusMessageProps): React.ReactElement {
  const styles = {
    info: "border-blue-200 bg-blue-50 text-blue-950",
    success: "border-emerald-200 bg-emerald-50 text-emerald-950",
    warning: "border-amber-200 bg-amber-50 text-amber-950",
    error: "border-red-200 bg-red-50 text-red-950",
  } as const;
  const Icons = { info: Info, success: CheckCircle2, warning: TriangleAlert, error: AlertCircle } as const;
  const Icon = Icons[tone];
  return (
    <div className={`flex gap-3 rounded-lg border p-3.5 ${styles[tone]}`} role={tone === "error" ? "alert" : live ? "status" : undefined} aria-live={live ? "polite" : undefined}>
      <Icon className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
      <div><p className="font-bold">{title}</p>{children ? <div className="mt-1 text-sm">{children}</div> : null}</div>
    </div>
  );
}
