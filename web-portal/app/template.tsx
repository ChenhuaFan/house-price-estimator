"use client";

import { m } from "motion/react";

export default function Template({ children }: { children: React.ReactNode }): React.ReactElement {
  return <m.div initial={{ opacity: 0.98 }} animate={{ opacity: 1 }} transition={{ duration: 0.16 }}>{children}</m.div>;
}
