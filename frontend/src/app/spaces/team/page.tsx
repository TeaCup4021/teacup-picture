import type { Metadata } from "next";
import { TeamSpaces } from "@/widgets/team-spaces";
export const metadata: Metadata = { title: "团队空间", robots: { index: false, follow: false } };
export default function TeamSpacesPage() { return <TeamSpaces />; }
