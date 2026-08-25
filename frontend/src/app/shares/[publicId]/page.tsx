import { ShareView } from "@/widgets/share-view";

export default async function SharedPicturePage({ params, searchParams }: {
  params: Promise<{ publicId: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { publicId } = await params;
  const query = await searchParams;
  const focusedThreadId = numericParam(query.thread);
  const focusedCommentId = focusedThreadId ? numericParam(query.comment) ?? focusedThreadId : undefined;
  return <ShareView publicId={publicId} focusedThreadId={focusedThreadId} focusedCommentId={focusedCommentId} />;
}

function numericParam(value: string | string[] | undefined): string | undefined {
  const candidate = Array.isArray(value) ? value[0] : value;
  return candidate && /^[1-9][0-9]*$/.test(candidate) ? candidate : undefined;
}
