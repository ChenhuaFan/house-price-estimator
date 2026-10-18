import { CheckCircle2, Database } from "lucide-react";
import Link from "next/link";
import { BrandMark } from "@/components/app-shell/brand-mark";
import { Navigation } from "@/components/app-shell/navigation";

export function AppHeader(): React.ReactElement {
  return (
    <>
      <header className="sticky top-0 z-30 border-b border-border-ui bg-white px-4 py-3 lg:hidden">
        <div className="mx-auto max-w-4xl">
          <Link href="/estimator" className="focus-ring flex min-h-11 items-center gap-2 rounded-lg" aria-label="Housing Intelligence mobile home">
            <BrandMark />
            <span className="text-lg font-extrabold leading-tight text-ink">Housing Intelligence</span>
          </Link>
          <div className="mt-2"><Navigation compact /></div>
        </div>
      </header>
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-56 flex-col bg-gradient-to-b from-shell to-shell-deep text-white lg:flex">
        <Link href="/estimator" className="focus-ring mx-5 mt-5 flex min-h-12 items-center gap-2 rounded-lg" aria-label="Housing Intelligence Portal home">
          <BrandMark />
          <span className="text-lg font-extrabold leading-5">Housing<br />Intelligence</span>
        </Link>
        <div className="mt-9 pr-3"><Navigation /></div>
        <div className="mx-5 mb-6 mt-auto border-t border-white/10 pt-5 text-xs text-slate-300">
          <div className="flex items-start gap-3">
            <Database className="mt-0.5 size-4 shrink-0 text-white" aria-hidden="true" />
            <div>
              <p className="font-bold text-white">Dataset</p>
              <p className="mt-1">Housing dataset</p>
              <p className="mt-2 flex items-center gap-1.5 text-emerald-400"><CheckCircle2 className="size-3.5" aria-hidden="true" /> Services connected</p>
            </div>
          </div>
        </div>
      </aside>
    </>
  );
}
