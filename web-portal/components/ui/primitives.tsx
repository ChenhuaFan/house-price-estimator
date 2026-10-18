import type { ButtonHTMLAttributes, HTMLAttributes, ReactNode } from "react";

const baseButton = "inline-flex min-h-11 cursor-pointer items-center justify-center gap-2 rounded-lg px-4 py-2.5 text-sm font-bold transition-colors duration-200 disabled:cursor-not-allowed disabled:opacity-45";

const buttonVariants = {
  primary: "border border-primary bg-primary text-white shadow-sm hover:border-primary-strong hover:bg-primary-strong",
  secondary: "border border-slate-300 bg-white text-ink hover:border-slate-400 hover:bg-slate-50",
  quiet: "border border-transparent bg-transparent text-ink-muted hover:bg-slate-100 hover:text-ink",
  danger: "border border-red-200 bg-white text-danger hover:border-danger hover:bg-red-50",
} as const;

export function buttonClassName(
  variant: keyof typeof buttonVariants = "primary",
  className = "",
): string {
  return `${baseButton} ${buttonVariants[variant]} ${className}`.trim();
}

export function Button({
  variant = "primary",
  className = "",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: keyof typeof buttonVariants }): React.ReactElement {
  return <button className={buttonClassName(variant, className)} {...props} />;
}

export function Panel({ className = "", ...props }: HTMLAttributes<HTMLElement>): React.ReactElement {
  return <section className={`rounded-xl border border-border-ui bg-white shadow-panel ${className}`.trim()} {...props} />;
}

export function Eyebrow({ children, className = "" }: { children: ReactNode; className?: string }): React.ReactElement {
  return <p className={`text-xs font-extrabold uppercase tracking-[0.12em] text-primary ${className}`.trim()}>{children}</p>;
}

export function Spinner({ label = "Loading" }: { label?: string }): React.ReactElement {
  return (
    <span className="inline-flex items-center gap-2">
      <span aria-hidden="true" className="size-4 animate-spin rounded-full border-2 border-current border-r-transparent" />
      <span>{label}</span>
    </span>
  );
}
