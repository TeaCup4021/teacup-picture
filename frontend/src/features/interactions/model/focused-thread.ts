import type { CommentItem, CommentPage } from "./types";

export function mergeFocusedThread(page?: CommentPage, focused?: CommentItem): CommentPage | undefined {
  if (!focused) return page;
  if (!page) return { items: [focused], hasMore: false, currentVersionId: focused.pictureVersionId };
  const index = page.items.findIndex((item) => item.id === focused.id);
  const items = index < 0
    ? [focused, ...page.items]
    : page.items.map((item, itemIndex) => itemIndex === index ? focused : item);
  return { ...page, items };
}
