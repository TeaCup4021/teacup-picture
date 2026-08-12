import { PictureDetail } from "@/widgets/picture-detail";

export default async function PictureDetailPage(props: PageProps<"/pictures/[pictureId]">) {
  const { pictureId } = await props.params;
  return <PictureDetail pictureId={pictureId} />;
}
