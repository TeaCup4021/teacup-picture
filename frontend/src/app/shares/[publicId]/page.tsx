import { ShareView } from "@/widgets/share-view";

export default async function SharedPicturePage({ params }: { params: Promise<{ publicId: string }> }) {
  const { publicId } = await params;
  return <ShareView publicId={publicId} />;
}
