import type { Metadata } from "next";
import { LoginScreen } from "@/widgets/login-screen";

export const metadata: Metadata = {
  title: "登录",
  robots: { index: false, follow: false },
};

export default function LoginPage() {
  return <LoginScreen />;
}
