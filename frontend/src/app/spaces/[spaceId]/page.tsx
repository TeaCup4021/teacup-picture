import type { Metadata } from "next";
import { TeamSpaceDetail } from "@/widgets/team-space-detail";
export const metadata: Metadata = { title: "团队空间", robots: { index: false, follow: false } };
export default async function TeamSpacePage({ params }: { params: Promise<{ spaceId: string }> }) { const { spaceId } = await params; return <TeamSpaceDetail spaceId={spaceId} />; }
