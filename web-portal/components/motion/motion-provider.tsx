"use client";

import { LazyMotion, MotionConfig, domAnimation } from "motion/react";

export function MotionProvider({ children }: { children: React.ReactNode }): React.ReactElement {
  return (
    <MotionConfig reducedMotion="user" transition={{ duration: 0.18, ease: "easeOut" }}>
      <LazyMotion features={domAnimation} strict>{children}</LazyMotion>
    </MotionConfig>
  );
}
