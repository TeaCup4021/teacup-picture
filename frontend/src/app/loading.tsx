"use client";

import { Skeleton } from "antd";

export default function Loading() {
  return (
    <main className="page-shell" aria-busy="true">
      <Skeleton active paragraph={{ rows: 6 }} />
    </main>
  );
}
