import type { Metadata, Viewport } from "next";
import { AppHeader } from "@/components/app-shell/app-header";
import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "Housing Intelligence Portal",
    template: "%s | Housing Intelligence Portal",
  },
  description: "Estimate property values and explore the supplied housing market dataset.",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  colorScheme: "light",
  themeColor: "#0f766e",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>): React.ReactElement {
  return (
    <html lang="en">
      <body>
        <a href="#main-content" className="skip-link">Skip to main content</a>
        <AppHeader />
        <main id="main-content" tabIndex={-1} className="mx-auto min-h-[calc(100dvh-12rem)] max-w-7xl px-4 py-8 outline-none sm:px-6 lg:px-8 lg:py-12">
          {children}
        </main>
        <footer className="border-t border-teal-100 bg-white">
          <div className="mx-auto flex max-w-7xl flex-col gap-1 px-4 py-6 text-sm text-slate-600 sm:px-6 lg:flex-row lg:items-center lg:justify-between lg:px-8">
            <p>Housing Intelligence Portal</p>
            <p>Predictions are estimates, not appraisals or financial advice.</p>
          </div>
        </footer>
      </body>
    </html>
  );
}
