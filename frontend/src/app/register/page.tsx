import type { Metadata } from "next";
import { RegisterScreen } from "@/widgets/register-screen";

export const metadata: Metadata = { title: "注册", robots: { index: false, follow: false } };

export default function RegisterPage() { return <RegisterScreen />; }
