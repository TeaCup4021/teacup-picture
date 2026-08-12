import type { ThemeConfig } from "antd";

export const appTheme: ThemeConfig = {
  token: {
    borderRadius: 6,
    borderRadiusLG: 8,
    colorBgBase: "#ffffff",
    colorBgContainer: "#ffffff",
    colorBgLayout: "#f2f5f8",
    colorBorder: "#dfe5ec",
    colorError: "#d9363e",
    colorInfo: "#3370ff",
    colorPrimary: "#3370ff",
    colorSuccess: "#1d9a72",
    colorText: "#1f2329",
    colorTextSecondary: "#646a73",
    colorWarning: "#d48806",
    controlHeight: 38,
    fontFamily: 'Inter, "PingFang SC", "Microsoft YaHei", system-ui, -apple-system, sans-serif',
  },
  components: {
    Button: {
      borderRadius: 6,
      controlHeightLG: 42,
      primaryShadow: "0 6px 16px rgb(51 112 255 / 18%)",
    },
    Card: {
      borderRadiusLG: 8,
    },
    Input: {
      activeBorderColor: "#3370ff",
      activeShadow: "0 0 0 3px rgb(51 112 255 / 12%)",
      hoverBorderColor: "#3370ff",
    },
    Segmented: {
      itemSelectedBg: "#ffffff",
      itemSelectedColor: "#245bdb",
      trackBg: "#edf1f5",
    },
  },
};
