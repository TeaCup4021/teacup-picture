"use client";

import { ReloadOutlined } from "@ant-design/icons";
import { Button, Result } from "antd";
import { useEffect } from "react";

export default function GlobalError({
  error,
  reset,
}: Readonly<{ error: Error & { digest?: string }; reset: () => void }>) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <main className="centered-state">
      <Result
        status="error"
        title="页面加载失败"
        subTitle="请稍后重试。"
        extra={
          <Button type="primary" icon={<ReloadOutlined />} onClick={reset}>
            重试
          </Button>
        }
      />
    </main>
  );
}
