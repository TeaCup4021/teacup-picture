import { render, screen } from "@testing-library/react";
import { App } from "antd";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { UploadScreen } from "@/widgets/upload-screen/upload-screen";

const { sessionMock } = vi.hoisted(() => ({ sessionMock: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock("@/features/prototype", () => ({
  m1Api: { previewPictureUrl: vi.fn() },
  usePrototypeSession: sessionMock,
  usePrototypeUpload: () => ({ isPending: false, mutate: vi.fn() }),
}));

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
});

class MockResizeObserver {
  observe() {
    return undefined;
  }

  unobserve() {
    return undefined;
  }

  disconnect() {
    return undefined;
  }
}

vi.stubGlobal("ResizeObserver", MockResizeObserver);

describe("UploadScreen access", () => {
  beforeEach(() => vi.clearAllMocks());

  it("shows the upload form to an authenticated administrator", () => {
    sessionMock.mockReturnValue({
      data: {
        id: "1",
        account: "admin",
        displayName: "管理员",
        role: "admin",
        avatarText: "管",
      },
      isLoading: false,
    });

    render(
      <App>
        <UploadScreen />
      </App>,
    );

    expect(screen.getByRole("heading", { name: "上传图片" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /保存到个人空间/ })).toBeInTheDocument();
  });

  it("keeps unauthenticated visitors out of the upload form", () => {
    sessionMock.mockReturnValue({ data: null, isLoading: false });

    render(
      <App>
        <UploadScreen />
      </App>,
    );

    expect(screen.getByText("登录后上传图片")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /保存到个人空间/ })).not.toBeInTheDocument();
  });
});
