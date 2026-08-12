import { expect, test } from "@playwright/test";

const visualBaselineEnabled = process.env.UI_VISUAL_BASELINE_APPROVED === "v1.1";

test.describe("@visual UI v1.1 baselines", () => {
  test.skip(
    !visualBaselineEnabled,
    "Enable only after the target page has passed UI v1.1 design review.",
  );

  test.beforeEach(async ({ page }) => {
    await page.emulateMedia({ reducedMotion: "reduce" });
  });

  const stabilizePage = async (page: import("@playwright/test").Page) => {
    await page.addStyleTag({
      content: `
        *, *::before, *::after {
          animation-delay: 0s !important;
          animation-duration: 0s !important;
          caret-color: transparent !important;
          transition-delay: 0s !important;
          transition-duration: 0s !important;
        }
      `,
    });
    await page.locator("img").evaluateAll(async (images) => {
      await Promise.all(
        images.map((element) => {
          const image = element as HTMLImageElement;
          return image.complete
            ? Promise.resolve()
            : new Promise<void>((resolve) => {
                image.addEventListener("load", () => resolve(), { once: true });
                image.addEventListener("error", () => resolve(), { once: true });
              });
        }),
      );
    });
  };

  test("public gallery desktop", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "公开图库" })).toBeVisible();
    await stabilizePage(page);
    await expect(page).toHaveScreenshot("public-gallery-desktop.png", {
      animations: "disabled",
      fullPage: true,
    });
  });

  test("public gallery mobile", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "公开图库" })).toBeVisible();
    await stabilizePage(page);
    await expect(page).toHaveScreenshot("public-gallery-mobile.png", {
      animations: "disabled",
      fullPage: true,
    });
  });

  test("login desktop", async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto("/login");
    await expect(page.getByRole("heading", { name: "登录" })).toBeVisible();
    await stabilizePage(page);
    await expect(page).toHaveScreenshot("login-desktop.png", {
      animations: "disabled",
      fullPage: true,
    });
  });
});
