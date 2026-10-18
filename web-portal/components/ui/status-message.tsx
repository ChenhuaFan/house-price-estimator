interface StatusMessageProps {
  tone?: "info" | "success" | "warning" | "error";
  title: string;
  children?: React.ReactNode;
  live?: boolean;
}

export function StatusMessage({
  tone = "info",
  title,
  children,
  live = false,
}: StatusMessageProps): React.ReactElement {
  return (
    <div
      className={`status-message status-${tone}`}
      role={tone === "error" ? "alert" : live ? "status" : undefined}
      aria-live={live ? "polite" : undefined}
    >
      <p className="font-semibold">{title}</p>
      {children ? <div className="mt-1 text-sm">{children}</div> : null}
    </div>
  );
}
