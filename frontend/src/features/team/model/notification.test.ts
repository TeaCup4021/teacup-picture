import { describe, expect, it } from "vitest";
import type { NotificationItem } from "./types";
import { notificationHref, notificationTitle } from "./notification";

function notification(overrides: Partial<NotificationItem> = {}): NotificationItem {
  return {
    id: "1",
    type: "picture_comment_created",
    resourceType: "picture_comment",
    resourceId: "31",
    payload: {
      pictureId: "21",
      rootId: "31",
      commentId: "31",
      kind: "comment",
    },
    createdAt: "2026-08-25T08:00:00Z",
    ...overrides,
  };
}

describe("notification links", () => {
  it("builds a picture deep link to the exact discussion target", () => {
    expect(notificationHref(notification())).toBe("/pictures/21?thread=31&comment=31");
  });

  it("uses a share link when the recipient only has share access", () => {
    expect(notificationHref(notification({
      type: "picture_comment_reply",
      resourceId: "42",
      payload: {
        pictureId: "21",
        rootId: "31",
        commentId: "42",
        kind: "comment",
        sharePublicId: "share_Ab-12",
      },
    }))).toBe("/shares/share_Ab-12?thread=31&comment=42");
  });

  it("rejects malformed identifiers instead of creating an unsafe route", () => {
    expect(notificationHref(notification({ payload: {
      pictureId: "../admin",
      rootId: "31",
      commentId: "42",
    } }))).toBeNull();
    expect(notificationHref(notification({ payload: {
      pictureId: "21",
      rootId: "31",
      commentId: "42",
      sharePublicId: "bad/value",
    } }))).toBe("/pictures/21?thread=31&comment=42");
  });
});

describe("notification labels", () => {
  it("distinguishes comments and annotations", () => {
    expect(notificationTitle(notification())).toBe("评论了你的图片");
    expect(notificationTitle(notification({ payload: {
      pictureId: "21",
      rootId: "31",
      commentId: "31",
      kind: "annotation",
    } }))).toBe("在你的图片中添加了位置批注");
    expect(notificationTitle(notification({
      type: "picture_comment_reply",
      payload: {
        pictureId: "21",
        rootId: "31",
        commentId: "42",
        kind: "annotation",
      },
    }))).toBe("回复了你的批注");
  });
});
