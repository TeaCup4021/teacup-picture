import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await page.evaluate(() => window.localStorage.clear());
});

test("renders the public gallery shell", async ({ page }) => {
  await page.goto("/");

  await expect(page).toHaveTitle(/茶杯图库/);
  await expect(page.getByRole("heading", { name: "公开图库" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "峡湾之上" })).toBeVisible();
});

test("uploads, reviews, and publishes a picture", async ({ page }) => {
  await page.goto("/login");
  await page.getByRole("button", { name: /登\s*录/ }).click();

  await expect(page).toHaveURL(/\/spaces\/personal$/);
  await page.getByRole("link", { name: /上传图片/ }).click();
  await page
    .locator('input[type="file"]')
    .setInputFiles("D:/teacup-picture/frontend/public/mock-images/gallery-06.jpg");
  await page.getByLabel("图片名称").fill("原型流程图片");
  await page.getByLabel("简介").fill("从上传到公开展示的端到端原型验证");
  await page.getByRole("button", { name: "保存到个人空间" }).click();

  await expect(page).toHaveURL(/\/pictures\/3\d+$/);
  await page.getByRole("button", { name: "提交公开审核" }).click();
  await expect(page.getByText("正在等待管理员审核")).toBeVisible();

  await page.getByRole("button", { name: /木一/ }).click();
  await page.getByText("退出登录").click();
  await expect(page).toHaveURL(/\/$/);
  await page.goto("/login");
  await page.getByRole("button", { name: "管理员" }).click();
  await expect(page.getByRole("textbox", { name: "账号", exact: true })).toHaveValue("admin");
  await expect(page.getByRole("textbox", { name: "密码", exact: true })).toHaveValue("admin123");
  await page.getByRole("button", { name: /登\s*录/ }).click();

  await expect(page).toHaveURL(/\/admin\/reviews$/);
  const reviewRow = page.getByRole("row", { name: /原型流程图片/ });
  await reviewRow.getByRole("button", { name: "通过" }).click();
  await expect(reviewRow).toBeHidden();

  await page.getByRole("link", { name: "公开图库" }).click();
  await expect(page.getByRole("heading", { name: "原型流程图片" })).toBeVisible();
});
