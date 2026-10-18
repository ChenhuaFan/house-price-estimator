import type { Metadata } from "next";
import { MarketDashboard } from "@/features/market/market-dashboard";
import { getInitialMarketAnalysis, marketQueryString } from "@/lib/server/backend";

export const metadata: Metadata = { title: "Market analysis" };

export default async function MarketPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}): Promise<React.ReactElement> {
  const values = await searchParams;
  const analysis = await getInitialMarketAnalysis(values);
  const filterValues = Object.fromEntries(marketQueryString(values)) as Record<string, string>;
  return (
    <div className="space-y-8">
      <header className="max-w-4xl">
        <p className="eyebrow">Property market analysis</p>
        <h1 className="mt-2 text-4xl font-bold tracking-tight text-slate-950 sm:text-5xl">Explore the evidence behind the market.</h1>
        <p className="mt-4 max-w-3xl text-lg text-slate-700">Filter all 50 supplied source properties, compare consistent aggregates and charts, test a hypothetical home, and export the same segment.</p>
      </header>
      <MarketDashboard analysis={analysis} filterValues={filterValues} />
    </div>
  );
}
