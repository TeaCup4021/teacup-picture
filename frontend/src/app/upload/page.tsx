import type { Metadata } from "next";
import { UploadScreen } from "@/widgets/upload-screen";



export const metadata: Metadata = {
  title: "上传图片",
  robots: { index: false, follow: false },
};



export default async function UploadPage({ searchParams }: { searchParams: Promise<{ spaceId?: string }> }) {
  const { spaceId } = await searchParams;
  return <UploadScreen spaceId={spaceId} />;
  
}
