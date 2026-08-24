import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "链接分享 - 茶杯图库",
  robots: { index: false, follow: false, nocache: true },
  referrer: "no-referrer",
};

export default function SharesLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
