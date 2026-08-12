import { spawnSync } from "node:child_process";
import path from "node:path";

const approvalToken = "v1.1";

if (process.env.UI_VISUAL_BASELINE_APPROVED !== approvalToken) {
  console.log(
    `Visual baselines are disabled. Set UI_VISUAL_BASELINE_APPROVED=${approvalToken} only after design review.`,
  );
  process.exit(0);
}

const playwrightCli = path.resolve("node_modules", "@playwright", "test", "cli.js");
const result = spawnSync(
  process.execPath,
  [playwrightCli, "test", "e2e/visual-baseline.spec.ts", ...process.argv.slice(2)],
  { stdio: "inherit" },
);

process.exit(result.status ?? 1);
