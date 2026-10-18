"use client";

import { BarChart3, House } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";

const links = [
  { href: "/estimator", label: "Property estimator", icon: House },
  { href: "/market", label: "Market analysis", icon: BarChart3 },
];

export function Navigation({ compact = false }: { compact?: boolean }): React.ReactElement {
  const pathname = usePathname();
  return (
    <nav aria-label="Primary navigation" className={compact ? "flex gap-1" : "space-y-1"}>
      {links.map((link) => {
        const active = pathname.startsWith(link.href);
        const Icon = link.icon;
        return (
          <Link
            key={link.href}
            href={link.href}
            aria-current={active ? "page" : undefined}
            className={`relative flex min-h-11 items-center gap-3 rounded-r-lg px-5 text-sm font-semibold transition-colors duration-200 ${
              compact ? "flex-1 justify-center rounded-lg px-3" : ""
            } ${
              active
                ? compact ? "bg-primary-soft text-primary" : "bg-primary/15 text-white"
                : compact ? "text-ink-muted hover:bg-slate-100 hover:text-ink" : "text-slate-300 hover:bg-white/7 hover:text-white"
            }`}
          >
            {!compact && active ? <span aria-hidden="true" className="absolute inset-y-0 left-0 w-1 bg-primary" /> : null}
            <Icon className="size-5 shrink-0" strokeWidth={1.9} aria-hidden="true" />
            <span>{link.label}</span>
          </Link>
        );
      })}
    </nav>
  );
}
