import type { Metadata } from "next";
import { MarketDashboard } from "@/features/market/market-dashboard";
import { getMarketPageModel } from "@/features/market/server";

export const metadata: Metadata = { title: "Market analysis" };

export default async function MarketPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}): Promise<React.ReactElement> {
  const initialModel = await getMarketPageModel(await searchParams);
  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-3xl font-extrabold tracking-tight text-ink sm:text-4xl">Market analysis</h1>
        <p className="mt-1 text-sm text-ink-muted sm:text-base">Explore housing data, identify trends, and run what-if estimates.</p>
      </header>
      <MarketDashboard initialModel={initialModel} />
    </div>
  );
}
