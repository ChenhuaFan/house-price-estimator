import Link from "next/link";
import { BrandMark } from "@/components/app-shell/brand-mark";
import { Navigation } from "@/components/app-shell/navigation";

export function AppHeader(): React.ReactElement {
  return (
    <header className="border-b border-teal-100 bg-white/95 backdrop-blur-sm">
      <div className="mx-auto flex max-w-7xl flex-col gap-4 px-4 py-4 sm:px-6 lg:flex-row lg:items-center lg:justify-between lg:px-8">
        <Link href="/estimator" className="focus-ring flex min-h-11 items-center gap-3 rounded-xl" aria-label="Housing Intelligence Portal home">
          <BrandMark />
          <span>
            <span className="block text-sm font-semibold tracking-wide text-teal-700">HOUSING INTELLIGENCE</span>
            <span className="block text-xs text-slate-600">Value and market evidence in one place</span>
          </span>
        </Link>
        <Navigation />
      </div>
    </header>
  );
}
