import { expect, test } from "@playwright/test";

type EditorStateEnvelope = {
  data: {
    editorState: {
      schemaVersion: number;
      crop: { x: number; y: number; width: number; height: number } | null;
      transform: { rotation: number; scale: number; flipX: boolean; flipY: boolean };
      adjustments: Record<string, number>;
      layers: Array<{
        type: string;
        tool?: string;
        text?: string;
        opacity?: number;
        width?: number;
        scaleX: number;
        scaleY: number;
        flipX: boolean;
        flipY: boolean;
      }>;
    } | null;
    revision: string | null;
  };
};

type VersionsEnvelope = {
  data: {
    items: Array<{
      id: string;
      versionNumber: number;
      name: string;
      sourceType: string;
      parentVersionId: string | null;
    }>;
  };
};

type PictureEnvelope = {
  data: { id: string; name: string; visibility: string; publishStatus: string };
};

test("M3 editor completes editing, replace, save-as, and restore flows", async ({ page }) => {
  test.setTimeout(150_000);
  const suffix = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
  const account = `m3u${suffix}`;
  const password = `M3pass${suffix}`;
  const apiBaseUrl = process.env.E2E_API_BASE_URL ?? "http://127.0.0.1:8123/api/v1";

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
  const sessionContext = await page.evaluate(() =>
    window.sessionStorage.getItem("teacup.session-context"),
  );
  expect(sessionContext).toBeTruthy();
  const getWithSession = (url: string) =>
    page.request.get(url, {
      headers: { "X-Teacup-Session-Context": sessionContext! },
    });

  await page.goto("/upload");
  await page.locator('input[type="file"]').setInputFiles("public/mock-images/gallery-06.jpg");
  await page.getByLabel("图片名称").fill(`M3 编辑器验收 ${suffix}`);
  await page.getByLabel("简介").fill("真实后端、MinIO 和 EditorState v3 闭环验收");
  await page.getByRole("button", { name: "保存到个人空间" }).click();
  await page.waitForURL(/\/pictures\/\d+$/);
  const pictureId = page.url().match(/\/pictures\/(\d+)/)?.[1];
  expect(pictureId).toBeTruthy();
  const originalContentResponse = await getWithSession(
    `${apiBaseUrl}/pictures/${pictureId}/content?variant=original`,
  );
  if (!originalContentResponse.ok()) {
    throw new Error(
      `Original content request failed with ${originalContentResponse.status()}: ${await originalContentResponse.text()}`,
    );
  }
  const originalContent = await originalContentResponse.body();
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
  await expect(page.getByRole("tab", { name: /图层/ })).toHaveCount(0);
  await page.waitForTimeout(1_200);
  expect(draftWrites).toEqual([]);

  const initialCanvas = await page
    .locator(".editor-base-canvas")
    .evaluate((canvas) => (canvas as HTMLCanvasElement).toDataURL("image/png"));
  const upperBackground = await page
    .locator(".upper-canvas")
    .evaluate((canvas) => getComputedStyle(canvas).backgroundColor);
  expect(upperBackground).toBe("rgba(0, 0, 0, 0)");

  const initialCanvasBox = await page.locator(".editor-canvas-stack").boundingBox();
  expect(initialCanvasBox).not.toBeNull();
  await expect(page.getByRole("button", { name: "撤销" })).toBeDisabled();
  await page.getByRole("button", { name: "放大" }).click();
  const zoomedCanvasBox = await page.locator(".editor-canvas-stack").boundingBox();
  expect(zoomedCanvasBox!.width).toBeGreaterThan(initialCanvasBox!.width);
  await expect(page.getByRole("button", { name: "撤销" })).toBeDisabled();
  await page.getByRole("button", { name: "适应窗口" }).click();

  const initialExposure = page.getByRole("slider", { name: "曝光", exact: true });
  const exposureRow = page.locator(".editor-adjustment-row").filter({ has: initialExposure });
  await exposureRow.getByText("-50", { exact: true }).click();
  await expect(initialExposure).toHaveAttribute("aria-valuenow", "-50");
  await exposureRow.getByText("0", { exact: true }).click();
  await expect(initialExposure).toHaveAttribute("aria-valuenow", "0");
  await initialExposure.focus();
  await initialExposure.press("ArrowRight");
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
  const discardedInitialDraft = (await (
    await getWithSession(`${apiBaseUrl}/pictures/${pictureId}/editor-state`)
  ).json()) as EditorStateEnvelope;
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
    .locator(".editor-base-canvas")
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
  await page.getByRole("button", { name: "选择", exact: true }).click();
  await page.getByRole("tab", { name: "对象属性" }).click();
  await expect(page.getByRole("textbox", { name: "内容" })).toHaveValue("验收文字");
  const textWidth = page.getByRole("spinbutton", { name: "文本框宽度" });
  await textWidth.fill("260");
  await textWidth.press("Enter");
  await expect(textWidth).toHaveValue("260");
  const horizontalLayerFlip = page
    .locator(".editor-layer-flips")
    .getByRole("button")
    .filter({ hasText: "水平翻转" });
  await horizontalLayerFlip.click();
  await expect(horizontalLayerFlip).toHaveClass(/ant-btn-primary/);

  const baseBeforeDrawing = await page
    .locator(".editor-base-canvas")
    .evaluate((canvas) => (canvas as HTMLCanvasElement).toDataURL("image/png"));

  for (const tool of ["画笔", "马克笔"] as const) {
    await page.getByRole("button", { name: tool, exact: true }).click();
    canvasBox = await page.locator(".upper-canvas").boundingBox();
    expect(canvasBox).not.toBeNull();
    const offset = tool === "画笔" ? 0 : tool === "马克笔" ? 80 : 40;
    await page.mouse.move(canvasBox!.x + 100 + offset, canvasBox!.y + 100);
    await page.mouse.down();
    await page.mouse.move(canvasBox!.x + 220 + offset, canvasBox!.y + 180, { steps: 8 });
    await page.mouse.up();
  }
  const overlayBeforeErase = await page
    .locator(".lower-canvas")
    .evaluate((canvas) => (canvas as HTMLCanvasElement).toDataURL("image/png"));
  await page.getByRole("button", { name: "擦除", exact: true }).click();
  await expect(page.getByRole("button", { name: "擦除", exact: true })).toHaveClass(/is-active/);
  canvasBox = await page.locator(".upper-canvas").boundingBox();
  await page.mouse.move(canvasBox!.x + 205, canvasBox!.y + 80);
  await expect(page.locator(".editor-eraser-cursor")).toBeVisible();
  expect(
    await page
      .locator(".editor-eraser-cursor")
      .evaluate((element) => getComputedStyle(element).backgroundColor),
  ).toBe("rgba(142, 151, 163, 0.28)");
  await page.mouse.down();
  await page.mouse.move(canvasBox!.x + 205, canvasBox!.y + 220, { steps: 8 });
  await page.mouse.up();
  const overlayAfterErase = await page
    .locator(".lower-canvas")
    .evaluate((canvas) => (canvas as HTMLCanvasElement).toDataURL("image/png"));
  const baseAfterErase = await page
    .locator(".editor-base-canvas")
    .evaluate((canvas) => (canvas as HTMLCanvasElement).toDataURL("image/png"));
  expect(overlayAfterErase).not.toBe(overlayBeforeErase);
  expect(baseAfterErase).toBe(baseBeforeDrawing);

  await page.getByRole("button", { name: "裁切", exact: true }).click();
  await page.getByRole("button", { name: "应用裁切" }).click();
  await page.getByRole("button", { name: "向右旋转" }).click();
  await page.getByRole("button", { name: "水平翻转画布" }).click();
  await page.getByRole("button", { name: "撤销" }).click();
  await page.getByRole("button", { name: "重做" }).click();
  await page.waitForTimeout(1_200);

  const draft = (await (
    await getWithSession(`${apiBaseUrl}/pictures/${pictureId}/editor-state`)
  ).json()) as EditorStateEnvelope;
  const draftDocument = draft.data.editorState!;
  expect(draftDocument).toMatchObject({
    schemaVersion: 3,
    crop: expect.any(Object),
    transform: { flipX: true, flipY: false },
  });
  expect(Number(draft.data.revision)).toBeGreaterThan(0);
  expect(draftDocument.adjustments).toMatchObject({ highlights: 1, shadows: 1, sharpness: 1 });
  expect(draftDocument.layers.filter((layer) => layer.type === "drawing")).toHaveLength(3);
  expect(draftDocument.layers.find((layer) => layer.tool === "marker")?.opacity).toBe(0.35);
  expect(
    draftDocument.layers.some((layer) => layer.type === "text" && layer.text === "验收文字"),
  ).toBe(true);
  const textLayer = draftDocument.layers.find(
    (layer) => layer.type === "text" && layer.text === "验收文字",
  );
  expect(textLayer).toMatchObject({ width: 260, flipX: true });
  expect(draftDocument.layers.every((layer) => layer.scaleX >= 0.01 && layer.scaleY >= 0.01)).toBe(
    true,
  );

  const download = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("button", { name: /导出/ }).click(),
  ]);
  expect(download[0].suggestedFilename()).toMatch(/\.png$/);

  await page.getByRole("button", { name: "保存图片" }).click();
  const saveDialog = page.getByRole("dialog", { name: "保存图片" });
  await expect(saveDialog.getByText("替换当前图片", { exact: true })).toBeVisible();
  await expect(saveDialog.getByText("另存为新图片", { exact: true })).toBeVisible();
  await saveDialog.getByRole("button", { name: "确认保存" }).click();
  await page.waitForURL(`/pictures/${pictureId}`);
  const draftAfterReplace = (await (
    await getWithSession(`${apiBaseUrl}/pictures/${pictureId}/editor-state`)
  ).json()) as EditorStateEnvelope;
  expect(draftAfterReplace.data.editorState).toBeNull();
  expect(draftAfterReplace.data.revision).toBeNull();

  const replacedContentResponse = await getWithSession(
    `${apiBaseUrl}/pictures/${pictureId}/content?variant=original`,
  );
  const replacedContent = await replacedContentResponse.body();
  expect(Buffer.compare(replacedContent, originalContent)).not.toBe(0);

  let versionHistory = (await (
    await getWithSession(`${apiBaseUrl}/pictures/${pictureId}/versions`)
  ).json()) as VersionsEnvelope;
  expect(versionHistory.data.items.map((version) => version.sourceType)).toEqual([
    "user_save",
    "original",
  ]);
  const originalVersion = versionHistory.data.items.find(
    (version) => version.sourceType === "original",
  );
  expect(originalVersion).toBeTruthy();

  await page.getByRole("link", { name: /编辑图片/ }).click();
  await page.waitForURL(`/editor/${pictureId}`);
  await page.locator(".upper-canvas").waitFor({ state: "visible" });
  await page.getByRole("slider", { name: "亮度", exact: true }).press("ArrowRight");
  await page.getByRole("button", { name: "保存图片" }).click();
  const copyDialog = page.getByRole("dialog", { name: "保存图片" });
  await copyDialog.getByText("另存为新图片", { exact: true }).click();
  const copyName = `M3 另存验收 ${suffix}`;
  await copyDialog.getByLabel("新图片名称").fill(copyName);
  await copyDialog.getByRole("button", { name: "确认保存" }).click();
  await page.waitForURL(/\/pictures\/\d+$/);
  const copyPictureId = page.url().match(/\/pictures\/(\d+)/)?.[1];
  expect(copyPictureId).toBeTruthy();
  expect(copyPictureId).not.toBe(pictureId);

  const copyDetail = (await (
    await getWithSession(`${apiBaseUrl}/pictures/${copyPictureId}`)
  ).json()) as PictureEnvelope;
  expect(copyDetail.data).toMatchObject({
    id: copyPictureId,
    name: copyName,
    visibility: "private",
    publishStatus: "not_requested",
  });
  const originalAfterCopy = await (
    await getWithSession(`${apiBaseUrl}/pictures/${pictureId}/content?variant=original`)
  ).body();
  expect(Buffer.compare(originalAfterCopy, replacedContent)).toBe(0);

  await page.goto(`/pictures/${pictureId}`);
  await page.getByRole("link", { name: /编辑图片/ }).click();
  await page.waitForURL(`/editor/${pictureId}`);
  await page.locator(".upper-canvas").waitFor({ state: "visible" });

  await page.getByRole("button", { name: /版本历史/ }).click();
  const originalVersionItem = page
    .locator(".version-list-item")
    .filter({ hasText: `v${originalVersion!.versionNumber} 原始图片` });
  await originalVersionItem.getByRole("button", { name: "恢复" }).click();
  await page.locator(".ant-popconfirm-buttons .ant-btn-primary").click();
  await page.getByText("已恢复为当前版本").waitFor();
  await page.waitForURL(`/pictures/${pictureId}`);
  const draftAfterRestore = (await (
    await getWithSession(`${apiBaseUrl}/pictures/${pictureId}/editor-state`)
  ).json()) as EditorStateEnvelope;
  expect(draftAfterRestore.data.editorState).toBeNull();
  expect(draftAfterRestore.data.revision).toBeNull();
  const restoredContent = await (
    await getWithSession(`${apiBaseUrl}/pictures/${pictureId}/content?variant=original`)
  ).body();
  expect(Buffer.compare(restoredContent, originalContent)).toBe(0);

  versionHistory = (await (
    await getWithSession(`${apiBaseUrl}/pictures/${pictureId}/versions`)
  ).json()) as VersionsEnvelope;
  expect(versionHistory.data.items.map((version) => version.sourceType)).toEqual([
    "restore",
    "user_save",
    "original",
  ]);
  expect(versionHistory.data.items[0]?.parentVersionId).toBe(originalVersion!.id);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`/editor/${pictureId}`);
  await page.locator(".upper-canvas").waitFor({ state: "visible" });
  await expect(page.getByRole("button", { name: /导出/ })).toBeVisible();
  await expect(page.getByRole("button", { name: "保存图片" })).toBeVisible();
  await expect(page.getByRole("button", { name: /退出编辑/ })).toContainText("退出");
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});
