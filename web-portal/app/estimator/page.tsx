import type { Metadata } from "next";
import { EstimatorWorkspace } from "@/features/estimator/estimator-workspace";
import { getEstimatorPageModel } from "@/features/estimator/server";

export const metadata: Metadata = { title: "Property estimator" };

export default async function EstimatorPage(): Promise<React.ReactElement> {
  const initialModel = await getEstimatorPageModel();
  return (
    <div className="mx-auto max-w-7xl space-y-5">
      <header>
        <h1 className="text-3xl font-extrabold tracking-tight text-ink sm:text-4xl">Property estimator</h1>
        <p className="mt-1 text-sm text-ink-muted sm:text-base">Enter all seven property characteristics, save each result, and compare estimates side by side.</p>
      </header>
      <EstimatorWorkspace initialModel={initialModel} />
    </div>
  );
}
