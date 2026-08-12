"use client";

import { HomeOutlined } from "@ant-design/icons";
import { Button, Result } from "antd";
import Link from "next/link";

export default function NotFound() {
  return (
    <main className="centered-state">
      <Result
        status="404"
        title="页面不存在"
        extra={
          <Button type="primary" icon={<HomeOutlined />}>
            <Link href="/">返回图库</Link>
          </Button>
        }
      />
    </main>
  );
}
