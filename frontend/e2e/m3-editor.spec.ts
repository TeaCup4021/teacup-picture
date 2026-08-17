import { expect, test } from "@playwright/test";

type EditorStateEnvelope = {
  data: {
    editorState: {
      schemaVersion: number;
      crop: { x: number; y: number; width: number; height: number } | null;
      transform: { rotation: number; scale: number };
      adjustments: Record<string, number>;
      layers: Array<{ type: string; tool?: string; text?: string; opacity?: number }>;
    } | null;
    revision: string | null;
  };
};

type VersionsEnvelope = {
  data: { items: Array<{ id: string; sourceType: string; parentVersionId: string | null }> };
};

test("M3 editor completes the structured edit, export, version, and restore flow", async ({
  page,
}) => {
  test.setTimeout(150_000);
  const suffix = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
  const account = `m3u${suffix}`;
  const password = `M3pass${suffix}`;

  await page.goto("/register");
  await page.getByRole("textbox", { name: "账号", exact: true }).fill(account);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByLabel("确认密码").fill(password);
  await page.getByRole("button", { name: "创建账号" }).click();
  await page.waitForURL(/\/login$/);
  await page.getByRole("textbox", { name: "账号", exact: true }).fill(account);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: /登\s*录/ }).click();
  await page.waitForURL(/\/spaces\/personal$/);

  await page.goto("/upload");
  await page.locator('input[type="file"]').setInputFiles("public/mock-images/gallery-06.jpg");
  await page.getByLabel("图片名称").fill(`M3 编辑器验收 ${suffix}`);
  await page.getByLabel("简介").fill("真实后端、MinIO 和 EditorState v2 闭环验收");
  await page.getByRole("button", { name: "保存到个人空间" }).click();
  await page.waitForURL(/\/pictures\/\d+$/);
  const pictureId = page.url().match(/\/pictures\/(\d+)/)?.[1];
  expect(pictureId).toBeTruthy();
  const editButton = page.getByRole("link", { name: /编辑图片/ });
  await expect(editButton).toBeVisible();

  const draftWrites: number[] = [];
  page.on("response", (response) => {
    if (
      response.request().method() === "PUT" &&
      response.url().endsWith(`/pictures/${pictureId}/editor-state`)
    ) {
      draftWrites.push(response.status());
    }
  });
  await editButton.click();
  await page.waitForURL(`/editor/${pictureId}`);
  await page.locator(".upper-canvas").waitFor({ state: "visible" });
  await page.waitForTimeout(1_200);
  expect(draftWrites).toEqual([]);

  const initialCanvas = await page
    .locator(".lower-canvas")
    .evaluate((canvas) => (canvas as HTMLCanvasElement).toDataURL("image/png"));
  const upperBackground = await page
    .locator(".upper-canvas")
    .evaluate((canvas) => getComputedStyle(canvas).backgroundColor);
  expect(upperBackground).toBe("rgba(0, 0, 0, 0)");

  const initialExposure = page.getByRole("slider", { name: "曝光", exact: true });
  const exposureBox = await initialExposure.boundingBox();
  expect(exposureBox).not.toBeNull();
  await page.mouse.move(
    exposureBox!.x + exposureBox!.width / 2,
    exposureBox!.y + exposureBox!.height / 2,
  );
  await page.mouse.down();
  await page.mouse.move(
    exposureBox!.x + exposureBox!.width * 0.8,
    exposureBox!.y + exposureBox!.height / 2,
    { steps: 20 },
  );
  await page.mouse.up();
  const draggedExposure = Number(await initialExposure.getAttribute("aria-valuenow"));
  expect(draggedExposure).toBeGreaterThan(0);
  await page.getByRole("button", { name: "撤销" }).click();
  await expect(initialExposure).toHaveAttribute("aria-valuenow", "0");
  await page.getByRole("button", { name: "重做" }).click();
  await expect(initialExposure).toHaveAttribute("aria-valuenow", String(draggedExposure));
  await expect.poll(() => draftWrites.filter((status) => status === 200).length).toBeGreaterThan(0);
  await page.getByRole("button", { name: /退出编辑/ }).click();
  const exitDialog = page.getByRole("dialog", { name: /退出编辑/ });
  await expect(exitDialog.getByRole("button", { name: "继续编辑" })).toBeVisible();
  await expect(exitDialog.getByRole("button", { name: "不保存并退出" })).toBeVisible();
  await expect(exitDialog.getByRole("button", { name: "保存草稿并退出" })).toBeVisible();
  await exitDialog.getByRole("button", { name: "继续编辑" }).click();
  await expect(page).toHaveURL(`/editor/${pictureId}`);
  await page.getByRole("button", { name: /退出编辑/ }).click();
  await page
    .getByRole("dialog", { name: /退出编辑/ })
    .getByRole("button", { name: "不保存并退出" })
    .click();
  await page.waitForURL(`/pictures/${pictureId}`);
  const discardedInitialDraft = await page.evaluate<EditorStateEnvelope, string>(async (id) => {
    const response = await fetch(`http://127.0.0.1:8123/api/v1/pictures/${id}/editor-state`, {
      credentials: "include",
    });
    return response.json();
  }, pictureId!);
  expect(discardedInitialDraft.data.editorState).toBeNull();
  expect(discardedInitialDraft.data.revision).toBeNull();
  draftWrites.length = 0;
  await page.getByRole("link", { name: /编辑图片/ }).click();
  await page.waitForURL(`/editor/${pictureId}`);
  await page.locator(".upper-canvas").waitFor({ state: "visible" });

  const adjustments = [
    "曝光",
    "亮度",
    "对比度",
    "高光",
    "阴影",
    "饱和度",
    "自然饱和度",
    "色温",
    "色调",
    "锐度",
    "褪色",
    "暗角",
    "增强",
    "去雾",
  ];
  for (const name of adjustments) {
    const slider = page.getByRole("slider", { name, exact: true });
    await slider.focus();
    await slider.press("ArrowRight");
  }
  await expect.poll(() => draftWrites.filter((status) => status === 200).length).toBeGreaterThan(0);
  const adjustedCanvas = await page
    .locator(".lower-canvas")
    .evaluate((canvas) => (canvas as HTMLCanvasElement).toDataURL("image/png"));
  expect(adjustedCanvas).not.toBe(initialCanvas);

  await page.getByRole("button", { name: "文字", exact: true }).click();
  let canvasBox = await page.locator(".upper-canvas").boundingBox();
  expect(canvasBox).not.toBeNull();
  await page.mouse.click(
    canvasBox!.x + canvasBox!.width * 0.5,
    canvasBox!.y + canvasBox!.height * 0.5,
  );
  await page.keyboard.type("验收文字");
  await page.waitForTimeout(450);
  await page.getByRole("button", { name: "选择", exact: true }).click();

  for (const tool of ["画笔", "马克笔", "擦除"] as const) {
    await page.getByRole("button", { name: tool, exact: true }).click();
    canvasBox = await page.locator(".upper-canvas").boundingBox();
    expect(canvasBox).not.toBeNull();
    const offset = tool === "画笔" ? 0 : tool === "马克笔" ? 80 : 40;
    await page.mouse.move(canvasBox!.x + 100 + offset, canvasBox!.y + 100);
    await page.mouse.down();
    await page.mouse.move(canvasBox!.x + 220 + offset, canvasBox!.y + 180, { steps: 8 });
    await page.mouse.up();
  }

  await page.getByRole("button", { name: "裁切", exact: true }).click();
  await page.getByRole("button", { name: "应用裁切" }).click();
  await page.getByRole("button", { name: "向右旋转" }).click();
  await page.getByRole("button", { name: "放大" }).click();
  await page.getByRole("button", { name: "撤销" }).click();
  await page.getByRole("button", { name: "重做" }).click();
  await page.waitForTimeout(1_200);

  const draft = await page.evaluate<EditorStateEnvelope, string>(async (id) => {
    const response = await fetch(`http://127.0.0.1:8123/api/v1/pictures/${id}/editor-state`, {
      credentials: "include",
    });
    return response.json();
  }, pictureId!);
  const draftDocument = draft.data.editorState!;
  expect(draftDocument).toMatchObject({ schemaVersion: 2, crop: expect.any(Object) });
  expect(Number(draft.data.revision)).toBeGreaterThan(0);
  expect(draftDocument.adjustments).toMatchObject({ highlights: 1, shadows: 1, sharpness: 1 });
  expect(draftDocument.layers.filter((layer) => layer.type === "drawing")).toHaveLength(3);
  expect(draftDocument.layers.find((layer) => layer.tool === "marker")?.opacity).toBe(0.35);
  expect(
    draftDocument.layers.some((layer) => layer.type === "text" && layer.text === "验收文字"),
  ).toBe(true);

  const download = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("button", { name: /导出/ }).click(),
  ]);
  expect(download[0].suggestedFilename()).toMatch(/\.png$/);

  await page.locator("button").filter({ hasText: "保存版本" }).first().click();
  await page.getByLabel("版本名称").fill("M3 E2E 验收版本");
  await page.getByLabel("版本说明").fill("全量编辑、导出与版本恢复");
  await page.getByRole("dialog").getByRole("button", { name: "保存版本", exact: true }).click();
  await page.getByText("已保存正式版本").waitFor({ timeout: 60_000 });

  const writesBeforeDiscard = draftWrites.length;
  await page.getByRole("slider", { name: "曝光", exact: true }).press("ArrowRight");
  await expect.poll(() => draftWrites.length).toBeGreaterThan(writesBeforeDiscard);
  await page.getByRole("button", { name: /退出编辑/ }).click();
  await page
    .getByRole("dialog", { name: /退出编辑/ })
    .getByRole("button", { name: "不保存并退出" })
    .click();
  await page.waitForURL(`/pictures/${pictureId}`);
  const discardedAfterVersion = await page.evaluate<EditorStateEnvelope, string>(async (id) => {
    const response = await fetch(`http://127.0.0.1:8123/api/v1/pictures/${id}/editor-state`, {
      credentials: "include",
    });
    return response.json();
  }, pictureId!);
  expect(discardedAfterVersion.data.editorState?.adjustments.exposure).toBe(1);
  await page.getByRole("link", { name: /编辑图片/ }).click();
  await page.waitForURL(`/editor/${pictureId}`);
  await page.locator(".upper-canvas").waitFor({ state: "visible" });

  await page.getByRole("button", { name: /版本历史/ }).click();
  await page.getByText(/v1 M3 E2E 验收版本/).waitFor();
  await page.locator(".ant-drawer button").filter({ hasText: "恢复" }).first().click();
  await page.locator(".ant-popconfirm-buttons .ant-btn-primary").click();
  await page.getByText("已恢复为当前版本").waitFor();

  await page.getByRole("slider", { name: "曝光", exact: true }).press("ArrowRight");
  await page.getByRole("button", { name: /退出编辑/ }).click();
  await page
    .getByRole("dialog", { name: /退出编辑/ })
    .getByRole("button", { name: "保存草稿并退出" })
    .click();
  await page.waitForURL(`/pictures/${pictureId}`);
  const savedOnExit = await page.evaluate<EditorStateEnvelope, string>(async (id) => {
    const response = await fetch(`http://127.0.0.1:8123/api/v1/pictures/${id}/editor-state`, {
      credentials: "include",
    });
    return response.json();
  }, pictureId!);
  expect(savedOnExit.data.editorState?.adjustments.exposure).toBe(2);
  expect(Number(savedOnExit.data.revision)).toBeGreaterThan(
    Number(discardedAfterVersion.data.revision ?? 0),
  );

  const versions = await page.evaluate<VersionsEnvelope, string>(async (id) => {
    const response = await fetch(`http://127.0.0.1:8123/api/v1/pictures/${id}/versions`, {
      credentials: "include",
    });
    return response.json();
  }, pictureId!);
  expect(versions.data.items.map((version) => version.sourceType)).toEqual([
    "restore",
    "user_save",
  ]);
  expect(versions.data.items[0]?.parentVersionId).toBe(versions.data.items[1]?.id);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`/editor/${pictureId}`);
  await page.locator(".upper-canvas").waitFor({ state: "visible" });
  await expect(page.getByRole("button", { name: /导出/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /保存版本/ })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
