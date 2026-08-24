import { expect, test } from "@playwright/test";

const adminAccount = process.env.M1_E2E_ADMIN_ACCOUNT;
const adminPassword = process.env.M1_E2E_ADMIN_PASSWORD;

test("renders the public gallery from the real API", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveTitle(/茶杯图库/);
  await expect(page.getByRole("heading", { name: "公开图库" })).toBeVisible();
  await expect(page.getByText(/张公开图片/)).toBeVisible();
});

test("registers, uploads, reviews, and publishes a picture", async ({ page, context }) => {
  test.skip(!adminAccount || !adminPassword, "Set M1_E2E_ADMIN_ACCOUNT and M1_E2E_ADMIN_PASSWORD");
  const suffix = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
  const account = `m1u${suffix}`;
  const password = `M1pass${suffix}`;
  const pictureName = `M1闭环图片${suffix}`;

  await page.goto("/register");
  await page.getByRole("textbox", { name: "账号", exact: true }).fill(account);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByLabel("确认密码").fill(password);
  await page.getByRole("button", { name: "创建账号" }).click();
  await expect(page).toHaveURL(/\/login$/);

  await page.getByRole("textbox", { name: "账号", exact: true }).fill(account);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: /登\s*录/ }).click();
  await expect(page).toHaveURL(/\/spaces\/personal$/);
  await expect.poll(async () => (await context.cookies()).some((cookie) => cookie.name === "TEACUP_SESSION")).toBe(true);

  await page
    .getByRole("navigation", { name: "工作台导航" })
    .getByRole("link", { name: /上传图片/ })
    .click();
  await page.locator('input[type="file"]').setInputFiles("public/mock-images/gallery-06.jpg");
  await page.getByLabel("图片名称").fill(pictureName);
  await page.getByLabel("简介").fill("真实后端上传、审核与公开图库闭环验证");
  await page.getByRole("button", { name: "保存到个人空间" }).click();
  await expect(page).toHaveURL(/\/pictures\/\d+$/);

  await page.getByRole("button", { name: "提交公开审核" }).click();
  await expect(page.getByText("正在等待管理员审核")).toBeVisible();

  await page.locator(".account-button").click();
  await page.getByText("退出登录").click();
  await page.goto("/login");
  await page.getByRole("textbox", { name: "账号", exact: true }).fill(adminAccount!);
  await page.getByLabel("密码", { exact: true }).fill(adminPassword!);
  await page.getByRole("button", { name: /登\s*录/ }).click();
  await expect(page).toHaveURL(/\/admin\/reviews$/);

  const reviewRow = page.getByRole("row", { name: new RegExp(pictureName) });
  await reviewRow.getByRole("button", { name: "通过" }).click();
  await expect(reviewRow).toBeHidden();

  await page.locator(".account-button").click();
  await page.getByText("退出登录").click();
  await page.goto("/");
  await expect(page.getByRole("heading", { name: pictureName })).toBeVisible();
});
