import { expect, test, type Page } from "@playwright/test";

async function register(page: Page, account: string, password: string) {
  await page.goto("/register");
  await page.getByRole("textbox", { name: "账号", exact: true }).fill(account);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByLabel("确认密码").fill(password);
  await page.getByRole("button", { name: "创建账号" }).click();
  await page.waitForURL(/\/login$/);
}

async function login(page: Page, account: string, password: string) {
  await page.getByRole("textbox", { name: "账号", exact: true }).fill(account);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: /登\s*录/ }).click();
}

async function selectFirstVisibleOption(page: Page) {
  await page
    .locator(".ant-select-dropdown:visible .ant-select-item-option")
    .first()
    .click();
}

async function expectStableResponsiveLayout(page: Page) {
  const result = await page.locator("main").evaluate((main) => {
    const viewportWidth = document.documentElement.clientWidth;
    const controls = Array.from(
      main.querySelectorAll<HTMLElement>('button, input, textarea, [role="combobox"]'),
    ).filter((element) => {
      const rect = element.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0;
    });
    const outOfBounds = controls
      .filter((element) => {
        const rect = element.getBoundingClientRect();
        return rect.left < -1 || rect.right > viewportWidth + 1;
      })
      .map((element) => element.getAttribute("aria-label") || element.textContent?.trim() || element.tagName);
    const siblingOverlaps: string[] = [];
    for (let left = 0; left < controls.length; left += 1) {
      for (let right = left + 1; right < controls.length; right += 1) {
        const leftControl = controls[left];
        const rightControl = controls[right];
        if (!leftControl || !rightControl || leftControl.parentElement !== rightControl.parentElement) continue;
        const a = leftControl.getBoundingClientRect();
        const b = rightControl.getBoundingClientRect();
        if (Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1) {
          siblingOverlaps.push(`${leftControl.tagName}/${rightControl.tagName}`);
        }
      }
    }
    return {
      horizontalOverflow: document.documentElement.scrollWidth - viewportWidth,
      outOfBounds,
      siblingOverlaps,
    };
  });
  expect(result.horizontalOverflow).toBeLessThanOrEqual(0);
  expect(result.outOfBounds).toEqual([]);
  expect(result.siblingOverlaps).toEqual([]);
}

test("M6 completes protected sharing, login continuation, comments, and annotation permissions", async ({ browser }, testInfo) => {
  test.setTimeout(180_000);
  const suffix = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
  const ownerAccount = `m6o${suffix}`;
  const viewerAccount = `m6v${suffix}`;
  const ownerPassword = `M6owner${suffix}`;
  const viewerPassword = `M6viewer${suffix}`;
  const sharePassword = `Share${suffix}`;
  const pictureName = `M6 分享批注验收 ${suffix}`;
  const ownerComment = `所有者讨论 ${suffix}`;
  const viewerComment = `访问者评论 ${suffix}`;
  const viewerReply = `结构化提及回复 ${suffix}`;
  const nestedReply = `回复已有回复 ${suffix}`;
  const annotation = `杯把高光批注 ${suffix}`;

  const viewerContext = await browser.newContext();
  const viewerPage = await viewerContext.newPage();
  viewerPage.setDefaultTimeout(15_000);
  await register(viewerPage, viewerAccount, viewerPassword);

  const ownerContext = await browser.newContext();
  const ownerPage = await ownerContext.newPage();
  ownerPage.setDefaultTimeout(15_000);
  await register(ownerPage, ownerAccount, ownerPassword);
  await login(ownerPage, ownerAccount, ownerPassword);
  await ownerPage.waitForURL(/\/spaces\/personal$/);

  await ownerPage.goto("/upload");
  await ownerPage.locator('input[type="file"]').setInputFiles("public/mock-images/gallery-06.jpg");
  await ownerPage.getByLabel("图片名称").fill(pictureName);
  await ownerPage.getByLabel("简介").fill("真实 MySQL、Redis、MinIO 与分享讨论闭环验收");
  await ownerPage.getByRole("button", { name: "保存到个人空间" }).click();
  await ownerPage.waitForURL(/\/pictures\/\d+$/);
  const pictureUrl = ownerPage.url();

  const ownerComposer = ownerPage.locator(".comment-composer");
  await ownerComposer.getByLabel("评论内容").fill(ownerComment);
  await ownerComposer.getByRole("button", { name: "发表" }).click();
  await expect(ownerPage.getByText(ownerComment, { exact: true })).toBeVisible();
  await expect(ownerPage).toHaveURL(pictureUrl);

  await ownerPage.getByRole("button", { name: /分享/ }).click();
  const shareDialog = ownerPage.getByRole("dialog", { name: /链接分享/ });
  await shareDialog.getByRole("switch").click();
  await shareDialog.getByLabel("分享访问密码").fill(sharePassword);
  await shareDialog.getByRole("button", { name: /生成链接/ }).click();
  await expect(shareDialog.locator(".share-link-result")).toBeVisible();
  const oldShareUrl = (await shareDialog.locator(".share-link-result .ant-typography").first().innerText()).trim();
  expect(oldShareUrl).toMatch(/^http:\/\/127\.0\.0\.1:\d+\/shares\/[A-Za-z0-9_-]+#[A-Za-z0-9_-]+$/);
  const oldShareLocation = new URL(oldShareUrl);
  const shareSecret = oldShareLocation.hash.slice(1);
  await shareDialog.locator(".ant-modal-close").click();

  await viewerPage.goto(oldShareUrl);
  await expect(viewerPage.getByRole("heading", { name: "此分享需要访问密码" })).toBeVisible();
  await viewerPage.getByLabel("访问密码").fill(sharePassword);
  await viewerPage.getByRole("button", { name: "验证并查看" }).click();
  await expect(viewerPage.getByRole("heading", { name: pictureName })).toBeVisible();
  await expect(viewerPage.getByText(ownerComment, { exact: true })).toBeVisible();
  await expect(viewerPage.getByText("登录后可发表评论、回复和批注")).toBeVisible();

  await viewerPage.getByRole("link", { name: "登录", exact: true }).click();
  await expect(viewerPage).toHaveURL(/\/login\?returnTo=/);
  const loginLocation = new URL(viewerPage.url());
  expect(loginLocation.searchParams.get("returnTo")).toBe(oldShareLocation.pathname);
  expect(loginLocation.href).not.toContain(shareSecret);
  await login(viewerPage, viewerAccount, viewerPassword);
  await expect(viewerPage).toHaveURL(oldShareUrl);
  await expect(viewerPage.getByRole("heading", { name: pictureName })).toBeVisible();

  const downloadPromise = viewerPage.waitForEvent("download");
  await viewerPage.getByRole("button", { name: /下载图片/ }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBeTruthy();

  const ownerThread = viewerPage.locator(".comment-thread").filter({ hasText: ownerComment });
  await expect(ownerThread.getByRole("button", { name: "解决" })).toHaveCount(0);
  await expect(ownerThread.getByRole("button", { name: "删除评论" })).toHaveCount(0);

  const viewerComposer = viewerPage.locator(".comment-composer");
  await viewerComposer.getByLabel("评论内容").fill(viewerComment);
  await viewerComposer.getByLabel("提及用户").click();
  await selectFirstVisibleOption(viewerPage);
  const commentRequestPromise = viewerPage.waitForRequest(
    (request) => request.method() === "POST" && /\/pictures\/\d+\/comments$/.test(request.url()),
  );
  await viewerComposer.getByRole("button", { name: "发表" }).click();
  const commentPayload = (await commentRequestPromise).postDataJSON() as { mentionedUserIds: string[] };
  expect(commentPayload.mentionedUserIds).toHaveLength(1);
  await expect(viewerPage.getByText(viewerComment, { exact: true })).toBeVisible();

  const viewerThread = viewerPage.locator(".comment-thread").filter({ hasText: viewerComment });
  await expect(viewerThread.getByRole("button", { name: "解决" })).toBeVisible();
  await expect(viewerThread.getByRole("button", { name: "删除评论" })).toBeVisible();
  await viewerThread.getByRole("button", { name: "回复", exact: true }).click();
  await viewerThread.getByLabel("回复内容").fill(viewerReply);
  await viewerThread.getByLabel("提及用户").click();
  await selectFirstVisibleOption(viewerPage);
  const replyRequestPromise = viewerPage.waitForRequest(
    (request) => request.method() === "POST" && /\/comments\/\d+\/replies$/.test(request.url()),
  );
  const replyResponsePromise = viewerPage.waitForResponse(
    (response) => response.request().method() === "POST" && /\/comments\/\d+\/replies$/.test(response.url()),
  );
  await viewerThread.locator(".reply-composer").getByRole("button", { name: /回\s*复/ }).click();
  const replyRequest = await replyRequestPromise;
  const replyPayload = replyRequest.postDataJSON() as { replyToId: string; mentionedUserIds: string[] };
  expect(replyPayload.mentionedUserIds).toHaveLength(1);
  const rootId = replyRequest.url().match(/\/comments\/(\d+)\/replies$/)?.[1];
  expect(replyPayload.replyToId).toBe(rootId);
  const replyResponse = await replyResponsePromise;
  const replyEnvelope = await replyResponse.json() as { data: { id: string } };
  await expect(viewerPage.getByText(viewerReply, { exact: true })).toBeVisible();

  const replyItem = viewerThread.locator(".comment-reply").filter({ hasText: viewerReply });
  await replyItem.getByRole("button", { name: "回复", exact: true }).click();
  await viewerThread.getByLabel("回复内容").fill(nestedReply);
  const nestedReplyRequestPromise = viewerPage.waitForRequest(
    (request) => request.method() === "POST" && /\/comments\/\d+\/replies$/.test(request.url()),
  );
  await viewerThread.locator(".reply-composer").getByRole("button", { name: /回\s*复/ }).click();
  const nestedReplyRequest = await nestedReplyRequestPromise;
  expect(nestedReplyRequest.url()).toMatch(new RegExp(`/comments/${rootId}/replies$`));
  expect((nestedReplyRequest.postDataJSON() as { replyToId: string }).replyToId).toBe(replyEnvelope.data.id);
  await expect(viewerPage.getByText(nestedReply, { exact: true })).toBeVisible();

  await viewerComposer.getByText("位置批注", { exact: true }).click();
  await viewerComposer.getByRole("button", { name: "在图片上选择位置" }).click();
  const imageFrame = viewerPage.locator(".detail-image-frame");
  await imageFrame.scrollIntoViewIfNeeded();
  const imageBox = await imageFrame.boundingBox();
  expect(imageBox).not.toBeNull();
  await viewerPage.mouse.click(imageBox!.x + imageBox!.width * 0.62, imageBox!.y + imageBox!.height * 0.38);
  await expect(viewerComposer.getByRole("button", { name: "已选择位置" })).toBeVisible();
  await viewerComposer.getByLabel("评论内容").fill(annotation);
  const annotationRequestPromise = viewerPage.waitForRequest(
    (request) => request.method() === "POST" && /\/pictures\/\d+\/comments$/.test(request.url()),
  );
  await viewerComposer.getByRole("button", { name: "发表" }).click();
  const annotationPayload = (await annotationRequestPromise).postDataJSON() as {
    kind: string;
    pictureVersionId: string;
    x: number;
    y: number;
  };
  expect(annotationPayload.kind).toBe("annotation");
  expect(annotationPayload.pictureVersionId).toMatch(/^\d+$/);
  expect(annotationPayload.x).toBeGreaterThan(0.55);
  expect(annotationPayload.x).toBeLessThan(0.7);
  expect(annotationPayload.y).toBeGreaterThan(0.3);
  expect(annotationPayload.y).toBeLessThan(0.46);
  await expect(viewerPage.locator('.annotation-pin[title="' + annotation + '"]')).toBeVisible();

  const annotationThread = viewerPage.locator(".comment-thread").filter({ hasText: annotation });
  const resolveResponsePromise = viewerPage.waitForResponse(
    (response) => response.request().method() === "PATCH" && /\/comments\/\d+$/.test(response.url()),
  );
  await annotationThread.getByRole("button", { name: "解决" }).click();
  const resolveResponse = await resolveResponsePromise;
  expect(resolveResponse.status()).toBe(200);
  expect((await resolveResponse.json()).data.resolved).toBe(true);
  await expect(annotationThread.getByText("已解决", { exact: true })).toBeVisible();
  const reopenResponsePromise = viewerPage.waitForResponse(
    (response) => response.request().method() === "PATCH" && /\/comments\/\d+$/.test(response.url()),
  );
  await annotationThread.getByRole("button", { name: "重新打开" }).click();
  const reopenResponse = await reopenResponsePromise;
  expect(reopenResponse.status()).toBe(200);
  expect((await reopenResponse.json()).data.resolved).toBe(false);
  await expect(annotationThread.getByRole("button", { name: "解决" })).toBeVisible();

  const deleteResponsePromise = viewerPage.waitForResponse(
    (response) => response.request().method() === "DELETE" && /\/comments\/\d+$/.test(response.url()),
  );
  await viewerThread.getByRole("button", { name: "删除评论" }).click();
  expect((await deleteResponsePromise).status()).toBe(200);
  await expect(viewerPage.getByText("该评论已删除", { exact: true })).toBeVisible();

  const viewports = [
    { name: "desktop", width: 1440, height: 900 },
    { name: "compact", width: 1024, height: 768 },
    { name: "mobile", width: 390, height: 844 },
  ];
  for (const viewport of viewports) {
    await ownerPage.setViewportSize(viewport);
    await viewerPage.setViewportSize(viewport);
    await ownerPage.evaluate(() => window.scrollTo(0, 0));
    await viewerPage.evaluate(() => window.scrollTo(0, 0));
    await expect(ownerPage.locator(".ant-message-notice")).toHaveCount(0, { timeout: 5_000 });
    await expect(viewerPage.locator(".ant-message-notice")).toHaveCount(0, { timeout: 5_000 });
    await expect(ownerPage.getByRole("heading", { name: pictureName })).toBeVisible();
    await expect(viewerPage.getByRole("heading", { name: pictureName })).toBeVisible();
    await expectStableResponsiveLayout(ownerPage);
    await expectStableResponsiveLayout(viewerPage);
    expect(await ownerPage.locator("main img").first().evaluate((image) => (image as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
    expect(await viewerPage.locator("main img").first().evaluate((image) => (image as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);
    await ownerPage.screenshot({ path: testInfo.outputPath(`owner-detail-${viewport.name}.png`), fullPage: true });
    await viewerPage.screenshot({ path: testInfo.outputPath(`authenticated-share-${viewport.name}.png`), fullPage: true });
  }
  await ownerPage.setViewportSize({ width: 1440, height: 900 });
  await viewerPage.setViewportSize({ width: 1440, height: 900 });

  await ownerPage.goto(pictureUrl);
  await ownerPage.getByRole("button", { name: /分享/ }).click();
  const regenerateDialog = ownerPage.getByRole("dialog", { name: /链接分享/ });
  await regenerateDialog.getByRole("button", { name: /重新生成/ }).click();
  await ownerPage.locator(".ant-popconfirm").getByRole("button", { name: /确\s*定/ }).click();
  await expect(regenerateDialog.locator(".share-link-result")).toBeVisible();
  const newShareUrl = (await regenerateDialog.locator(".share-link-result .ant-typography").first().innerText()).trim();
  expect(newShareUrl).not.toBe(oldShareUrl);

  await viewerPage.reload();
  await expect(viewerPage.getByText("分享不可用", { exact: true })).toBeVisible();

  const currentShareContext = await browser.newContext();
  const currentSharePage = await currentShareContext.newPage();
  currentSharePage.setDefaultTimeout(15_000);
  await currentSharePage.goto(newShareUrl);
  await expect(currentSharePage.getByRole("heading", { name: pictureName })).toBeVisible();

  await regenerateDialog.getByRole("button", { name: /撤销分享/ }).click();
  await ownerPage.locator(".ant-popconfirm").getByRole("button", { name: /确\s*定/ }).click();
  await expect(ownerPage.getByText("分享已撤销", { exact: true })).toBeVisible();
  await currentSharePage.reload();
  await expect(currentSharePage.getByText("分享不可用", { exact: true })).toBeVisible();

  await currentShareContext.close();
  await viewerContext.close();
  await ownerContext.close();
});

test("M6 owner withdraws an approved public picture", async ({ browser }) => {
  test.skip(
    !process.env.M1_E2E_ADMIN_ACCOUNT || !process.env.M1_E2E_ADMIN_PASSWORD,
    "Set M1_E2E_ADMIN_ACCOUNT and M1_E2E_ADMIN_PASSWORD",
  );
  test.setTimeout(120_000);
  const suffix = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
  const account = `m6p${suffix}`;
  const password = `M6publish${suffix}`;
  const pictureName = `M6 公开撤回验收 ${suffix}`;

  const ownerContext = await browser.newContext();
  const ownerPage = await ownerContext.newPage();
  ownerPage.setDefaultTimeout(15_000);
  await register(ownerPage, account, password);
  await login(ownerPage, account, password);
  await ownerPage.waitForURL(/\/spaces\/personal$/);
  await ownerPage.goto("/upload");
  await ownerPage.locator('input[type="file"]').setInputFiles("public/mock-images/gallery-06.jpg");
  await ownerPage.getByLabel("图片名称").fill(pictureName);
  await ownerPage.getByRole("button", { name: "保存到个人空间" }).click();
  await ownerPage.waitForURL(/\/pictures\/\d+$/);
  const pictureUrl = ownerPage.url();
  await ownerPage.getByRole("button", { name: "提交公开审核" }).click();
  await expect(ownerPage.getByText("正在等待管理员审核")).toBeVisible();

  const adminContext = await browser.newContext();
  const adminPage = await adminContext.newPage();
  adminPage.setDefaultTimeout(15_000);
  await adminPage.goto("/login");
  await login(adminPage, process.env.M1_E2E_ADMIN_ACCOUNT!, process.env.M1_E2E_ADMIN_PASSWORD!);
  await adminPage.waitForURL(/\/admin\/reviews$/);
  const reviewRow = adminPage.getByRole("row", { name: new RegExp(pictureName) });
  await reviewRow.getByRole("button", { name: "通过" }).click();
  await expect(reviewRow).toBeHidden();

  await ownerPage.goto(pictureUrl);
  await expect(ownerPage.getByRole("button", { name: /撤回公开/ })).toBeVisible();
  await ownerPage.getByRole("button", { name: /撤回公开/ }).click();
  await ownerPage.locator(".ant-popconfirm").getByRole("button", { name: /确\s*定/ }).click();
  await expect(ownerPage.getByText("已撤回公开状态", { exact: true })).toBeVisible();
  await expect(ownerPage.getByRole("button", { name: /撤回公开/ })).toHaveCount(0);
  await ownerPage.goto("/");
  await expect(ownerPage.getByRole("heading", { name: pictureName })).toHaveCount(0);

  await adminContext.close();
  await ownerContext.close();
});
