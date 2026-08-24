import { beforeEach, describe, expect, it } from "vitest";
import {
  clearShareLoginContinuation,
  rememberShareLoginContinuation,
  restoreShareLoginContinuation,
} from "./share-login-continuation";

describe("share login continuation", () => {
  beforeEach(() => window.sessionStorage.clear());

  it("keeps the fragment out of returnTo while restoring it in the same tab", () => {
    rememberShareLoginContinuation("public-id", "#fragment-secret");

    expect(restoreShareLoginContinuation("/shares/public-id")).toBe(
      "/shares/public-id#fragment-secret",
    );
    expect(restoreShareLoginContinuation("/spaces/personal")).toBe("/spaces/personal");

    clearShareLoginContinuation("public-id");
    expect(restoreShareLoginContinuation("/shares/public-id")).toBe("/shares/public-id");
  });
});
