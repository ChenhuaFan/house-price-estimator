"use client";

import { startTransition, useEffect } from "react";
import { useRouter } from "next/navigation";

export default function ErrorBoundary({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}): React.ReactElement {
  const router = useRouter();

  useEffect(() => {
    console.error("Portal route error", error.digest ?? error.name);
  }, [error]);

  function retry(): void {
    startTransition(() => {
      router.refresh();
      reset();
    });
  }

  return (
    <section className="card mx-auto max-w-2xl p-8 text-center" role="alert">
      <p className="eyebrow">Temporary problem</p>
      <h1 className="mt-2 text-3xl font-semibold text-slate-950">We could not load this page</h1>
      <p className="mt-3 text-slate-700">A required local service may still be starting. Retry now or use the navigation above.</p>
      <button type="button" onClick={retry} className="button-primary mt-6">Retry</button>
    </section>
  );
}
