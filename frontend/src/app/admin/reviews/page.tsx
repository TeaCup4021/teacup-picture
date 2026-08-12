import type { Metadata } from "next";
import { AdminReviews } from "@/widgets/admin-reviews";

export const metadata: Metadata = {
  title: "公开审核",
  robots: { index: false, follow: false },
};

export default function AdminReviewsPage() {
  return <AdminReviews />;
}
