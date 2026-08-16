import { Editor } from "@/widgets/editor";

export default async function EditorPage({ params }: { params: Promise<{ pictureId: string }> }) {
  const { pictureId } = await params;
  return <Editor pictureId={pictureId} />;
}
