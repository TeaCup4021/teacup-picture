import type { Metadata } from "next";
import { PersonalSpace } from "@/widgets/personal-space";

export const metadata: Metadata = {
  title: "个人空间",
  robots: { index: false, follow: false },
};

export default function PersonalSpacePage() {
  return <PersonalSpace />;
}
