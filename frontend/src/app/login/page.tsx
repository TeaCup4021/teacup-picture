import type { Metadata } from "next";
import { LoginScreen } from "@/widgets/login-screen";

export const metadata: Metadata = {
  title: "登录",
  robots: { index: false, follow: false },
};

export default async function LoginPage({ searchParams }: { searchParams: Promise<{ returnTo?: string | string[] }> }) {
  const raw = (await searchParams).returnTo;
  const value = Array.isArray(raw) ? raw[0] : raw;
  const returnTo = value?.startsWith("/") && !value.startsWith("//") ? value : null;
  return <LoginScreen returnTo={returnTo} />;
}
