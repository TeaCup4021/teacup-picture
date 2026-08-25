import { describe, expect, it } from "vitest";
import type { CommentItem, CommentPage } from "./types";
import { mergeFocusedThread } from "./focused-thread";

function thread(id: string, body = `thread-${id}`): CommentItem {
  return {
    id,
    pictureId: "21",
    pictureVersionId: "11",
    kind: "comment",
    body,
    author: { id: "1", name: "作者" },
    deleted: false,
    resolved: false,
    createdAt: "2026-08-25T08:00:00Z",
    updatedAt: "2026-08-25T08:00:00Z",
    replies: [],
    replyCount: 0,
    canDelete: false,
    canResolve: false,
  };
}

const page: CommentPage = {
  items: [thread("1"), thread("2")],
  nextCursor: "next",
  hasMore: true,
  currentVersionId: "12",
};

describe("mergeFocusedThread", () => {
  it("prepends a focused thread that is outside the loaded page", () => {
    const result = mergeFocusedThread(page, thread("9"));

    expect(result?.items.map((item) => item.id)).toEqual(["9", "1", "2"]);
    expect(result?.nextCursor).toBe("next");
    expect(result?.hasMore).toBe(true);
    expect(result?.currentVersionId).toBe("12");
  });

  it("replaces an already loaded thread with the complete focused thread", () => {
    const result = mergeFocusedThread(page, thread("2", "complete thread"));

    expect(result?.items.map((item) => item.id)).toEqual(["1", "2"]);
    expect(result?.items[1]?.body).toBe("complete thread");
  });
});
