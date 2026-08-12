"use client";

import {
  AppstoreOutlined,
  AuditOutlined,
  DownOutlined,
  LogoutOutlined,
  PictureOutlined,
  ReloadOutlined,
  UploadOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Avatar, Button, Dropdown, Skeleton, Tag } from "antd";
import type { MenuProps } from "antd";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { usePrototypeLogout, usePrototypeSession, useResetPrototype } from "@/features/prototype";

const navigation = [
  { href: "/", label: "公开图库", icon: <PictureOutlined /> },
  { href: "/spaces/personal", label: "个人空间", icon: <AppstoreOutlined /> },
  { href: "/upload", label: "上传", icon: <UploadOutlined /> },
];

export function AppChrome({ children }: Readonly<{ children: React.ReactNode }>) {
  const pathname = usePathname();
  const router = useRouter();
  const session = usePrototypeSession();
  const logout = usePrototypeLogout();
  const reset = useResetPrototype();

  if (pathname === "/login") return children;

  const menuItems: MenuProps["items"] = [
    {
      key: "personal",
      icon: <UserOutlined />,
      label: "个人空间",
      onClick: () => router.push("/spaces/personal"),
    },
    ...(session.data?.role === "admin"
      ? [
          {
            key: "reviews",
            icon: <AuditOutlined />,
            label: "审核管理",
            onClick: () => router.push("/admin/reviews"),
          },
        ]
      : []),
    { type: "divider" },
    {
      key: "reset",
      icon: <ReloadOutlined />,
      label: "重置原型数据",
      onClick: () => {
        reset();
        router.push("/");
      },
    },
    {
      key: "logout",
      icon: <LogoutOutlined />,
      label: "退出登录",
      onClick: () => logout.mutate(undefined, { onSuccess: () => router.push("/") }),
    },
  ];

  return (
    <div className="app-frame">
      <header className="global-header">
        <div className="header-inner">
          <Link className="brand-link" href="/" aria-label="茶杯图库首页">
            <span className="brand-mark">茶</span>
            <span>茶杯图库</span>
            <Tag variant="filled">M1 原型</Tag>
          </Link>
          <nav className="primary-nav" aria-label="主导航">
            {navigation.map((item) => (
              <Link
                className={pathname === item.href ? "nav-link is-active" : "nav-link"}
                href={item.href}
                key={item.href}
              >
                {item.icon}
                <span>{item.label}</span>
              </Link>
            ))}
            {session.data?.role === "admin" ? (
              <Link
                className={pathname.startsWith("/admin") ? "nav-link is-active" : "nav-link"}
                href="/admin/reviews"
              >
                <AuditOutlined />
                <span>审核管理</span>
              </Link>
            ) : null}
          </nav>
          <div className="header-account">
            {session.isLoading ? (
              <Skeleton.Avatar active size="small" />
            ) : session.data ? (
              <Dropdown menu={{ items: menuItems }} placement="bottomRight" trigger={["click"]}>
                <Button className="account-button" type="text">
                  <Avatar size={26}>{session.data.avatarText}</Avatar>
                  <span>{session.data.displayName}</span>
                  <DownOutlined />
                </Button>
              </Dropdown>
            ) : (
              <Button type="primary" href="/login">
                登录
              </Button>
            )}
          </div>
        </div>
      </header>
      {children}
    </div>
  );
}
