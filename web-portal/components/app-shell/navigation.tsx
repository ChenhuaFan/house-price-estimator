"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const links = [
  { href: "/estimator", label: "Property estimator" },
  { href: "/market", label: "Market analysis" },
];

export function Navigation(): React.ReactElement {
  const pathname = usePathname();
  return (
    <nav aria-label="Primary navigation" className="flex w-full gap-2 sm:w-auto">
      {links.map((link) => {
        const active = pathname.startsWith(link.href);
        return (
          <Link
            key={link.href}
            href={link.href}
            aria-current={active ? "page" : undefined}
            className={`nav-link ${active ? "nav-link-active" : ""}`}
          >
            {link.label}
          </Link>
        );
      })}
    </nav>
  );
}
