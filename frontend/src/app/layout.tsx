import type { Metadata } from "next";
import { AntdRegistry } from "@ant-design/nextjs-registry";
import { AppProviders } from "@/app/providers";
import { AppChrome } from "@/features/prototype/ui/app-chrome";
import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "茶杯图库",
    template: "%s | 茶杯图库",
  },
  description: "茶杯云图库",
};



export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>
        <AntdRegistry>
          <AppProviders>
            <AppChrome>{children}</AppChrome>
          </AppProviders>
        </AntdRegistry>
      </body>
    </html>
  );
}
