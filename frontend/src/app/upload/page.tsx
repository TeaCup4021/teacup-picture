import type { Metadata } from "next";
import { UploadScreen } from "@/widgets/upload-screen";

export const metadata: Metadata = {
  title: "上传图片",
  robots: { index: false, follow: false },
};

export default function UploadPage() {
  return <UploadScreen />;
}
