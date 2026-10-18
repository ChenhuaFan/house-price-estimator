import type { Metadata, Viewport } from "next";
import { AppHeader } from "@/components/app-shell/app-header";
import { MotionProvider } from "@/components/motion/motion-provider";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "Housing Intelligence Portal", template: "%s | Housing Intelligence Portal" },
  description: "Estimate property values and explore the supplied housing market dataset.",
};

export const viewport: Viewport = { width: "device-width", initialScale: 1, colorScheme: "light", themeColor: "#142333" };

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>): React.ReactElement {
  return (
    <html lang="en">
      <body>
        <MotionProvider>
          <a href="#main-content" className="skip-link">Skip to main content</a>
          <AppHeader />
          <div className="lg:ml-56">
            <main id="main-content" tabIndex={-1} className="min-h-dvh px-4 py-5 outline-none sm:px-6 lg:px-5 xl:px-6">{children}</main>
            <footer className="border-t border-border-ui bg-white px-5 py-4 text-xs text-ink-muted">
              <div className="flex flex-col gap-1 sm:flex-row sm:justify-between"><p>Housing Intelligence Portal</p><p>Predictions are estimates, not appraisals or financial advice.</p></div>
            </footer>
          </div>
        </MotionProvider>
      </body>
    </html>
  );
}
