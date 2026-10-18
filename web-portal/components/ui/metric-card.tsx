import type { LucideIcon } from "lucide-react";
import { Panel } from "@/components/ui/primitives";

export function MetricCard({
  label,
  value,
  icon: Icon,
  tone = "blue",
}: {
  label: string;
  value: string;
  icon: LucideIcon;
  tone?: "blue" | "green" | "red" | "orange";
}): React.ReactElement {
  const tones = {
    blue: "bg-blue-soft text-blue",
    green: "bg-green-soft text-green",
    red: "bg-primary-soft text-primary",
    orange: "bg-orange-soft text-orange",
  } as const;
  return (
    <Panel className="flex min-w-0 items-center gap-3 p-4">
      <span className={`grid size-11 shrink-0 place-items-center rounded-full ${tones[tone]}`} aria-hidden="true">
        <Icon className="size-5" strokeWidth={1.9} />
      </span>
      <span className="min-w-0">
        <span className="numeric block text-lg font-extrabold tracking-tight text-ink xl:text-xl">{value}</span>
        <span className="mt-0.5 block text-xs font-medium text-ink-muted sm:text-sm">{label}</span>
      </span>
    </Panel>
  );
}
