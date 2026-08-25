import { PictureDetail } from "@/widgets/picture-detail";

export default async function PictureDetailPage(props: PageProps<"/pictures/[pictureId]">) {
  const { pictureId } = await props.params;
  const searchParams = await props.searchParams;
  const focusedThreadId = numericParam(searchParams.thread);
  const focusedCommentId = focusedThreadId ? numericParam(searchParams.comment) ?? focusedThreadId : undefined;
  return <PictureDetail pictureId={pictureId} focusedThreadId={focusedThreadId} focusedCommentId={focusedCommentId} />;
}

function numericParam(value: string | string[] | undefined): string | undefined {
  const candidate = Array.isArray(value) ? value[0] : value;
  return candidate && /^[1-9][0-9]*$/.test(candidate) ? candidate : undefined;
}
