import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { App } from "antd";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { UploadScreen } from "@/widgets/upload-screen/upload-screen";

const { mutateMock, sessionMock } = vi.hoisted(() => ({ mutateMock: vi.fn(), sessionMock: vi.fn() }));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock("@/features/prototype", () => ({
  m1Api: { previewPictureUrl: vi.fn() },
  usePrototypeSession: sessionMock,
  usePrototypeUpload: () => ({ isPending: false, mutate: mutateMock }),
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
  beforeEach(() => {
    vi.clearAllMocks();
    const NativeUrl = URL;
    class TestUrl extends NativeUrl {}
    Object.assign(TestUrl, { createObjectURL: vi.fn(() => "blob:preview"), revokeObjectURL: vi.fn() });
    vi.stubGlobal("URL", TestUrl);
  });

  afterEach(() => vi.unstubAllGlobals());

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

  it("previews a local file with an object URL and passes cancellable progress callbacks", async () => {
    sessionMock.mockReturnValue({
      data: { id: "1", account: "user", displayName: "用户", role: "user", avatarText: "用" },
      isLoading: false,
    });
    const originalImage = window.Image;
    class MockImage {
      naturalWidth = 1200;
      naturalHeight = 800;
      onload: null | (() => void) = null;
      onerror: null | (() => void) = null;
      set src(_value: string) { queueMicrotask(() => this.onload?.()); }
    }
    vi.stubGlobal("Image", MockImage);

    render(<App><UploadScreen /></App>);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(["image"], "photo.png", { type: "image/png" })] } });

    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled());
    fireEvent.click(screen.getByRole("button", { name: /保存到个人空间/ }));
    await waitFor(() => expect(mutateMock).toHaveBeenCalled());
    expect(mutateMock.mock.calls[0]?.[0]).toMatchObject({
      file: expect.any(File),
      signal: expect.any(AbortSignal),
      onUploadProgress: expect.any(Function),
    });

    vi.stubGlobal("Image", originalImage);
  });
});
