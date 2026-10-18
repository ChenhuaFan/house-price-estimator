import type { Metadata } from "next";
import { EstimatorWorkspace } from "@/features/estimator/estimator-workspace";
import { getInitialHistory } from "@/lib/server/backend";

export const metadata: Metadata = { title: "Property estimator" };

export default async function EstimatorPage(): Promise<React.ReactElement> {
  const initialHistory = await getInitialHistory();
  return (
    <div className="space-y-8">
      <header className="max-w-4xl">
        <p className="eyebrow">Property value estimator</p>
        <h1 className="mt-2 text-4xl font-bold tracking-tight text-slate-950 sm:text-5xl">A clear estimate, backed by one reproducible model.</h1>
        <p className="mt-4 max-w-3xl text-lg text-slate-700">Enter seven property characteristics, retain previous results, and compare homes without losing the exact price or model version behind each estimate.</p>
      </header>
      <EstimatorWorkspace initialHistory={initialHistory} />
    </div>
  );
}
