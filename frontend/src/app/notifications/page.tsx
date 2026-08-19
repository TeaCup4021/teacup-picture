import type { Metadata } from "next";
import { NotificationCenter } from "@/widgets/notification-center";
export const metadata: Metadata = { title: "通知中心", robots: { index: false, follow: false } };
export default function NotificationsPage() { return <NotificationCenter />; }
