"use client";

import { startTransition, useEffect } from "react";
import { useRouter } from "next/navigation";
import { Button, Panel } from "@/components/ui/primitives";

export default function ErrorBoundary({ error, reset }: { error: Error & { digest?: string }; reset: () => void }): React.ReactElement {
  const router = useRouter();
  useEffect(() => { console.error("Portal route error", error.digest ?? error.name); }, [error]);
  function retry(): void {
    startTransition(() => { router.refresh(); reset(); });
  }
  return (
    <Panel className="mx-auto max-w-2xl p-8 text-center" role="alert">
      <p className="text-xs font-extrabold uppercase tracking-wider text-primary">Temporary problem</p>
      <h1 className="mt-2 text-3xl font-extrabold text-ink">We could not load this page</h1>
      <p className="mt-3 text-ink-muted">A required local service may still be starting. Retry now or use the navigation.</p>
      <Button type="button" onClick={retry} className="mt-6">Retry</Button>
    </Panel>
  );
}
