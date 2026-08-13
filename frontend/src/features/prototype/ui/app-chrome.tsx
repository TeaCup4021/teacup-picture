"use client";

import {
  AppstoreOutlined,
  AuditOutlined,
  DownOutlined,
  HomeOutlined,
  LogoutOutlined,
  PictureOutlined,
  UploadOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Avatar, Button, Dropdown, Skeleton } from "antd";
import type { MenuProps } from "antd";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { usePrototypeLogout, usePrototypeSession } from "@/features/prototype";

const publicNavigation = [
  { href: "/", label: "发现", icon: <PictureOutlined /> },
  { href: "/spaces/personal", label: "个人空间", icon: <AppstoreOutlined /> },
];

const workspaceNavigation = [
  { href: "/spaces/personal", label: "空间概览", icon: <HomeOutlined /> },
  { href: "/upload", label: "上传图片", icon: <UploadOutlined /> },
  { href: "/", label: "公开图库", icon: <PictureOutlined /> },
];

export function AppChrome({ children }: Readonly<{ children: React.ReactNode }>) {
  const pathname = usePathname();
  const router = useRouter();
  const session = usePrototypeSession();
  const logout = usePrototypeLogout();
  if (pathname === "/login" || pathname === "/register") return children;

  const isWorkspace = pathname.startsWith("/spaces/") || pathname === "/upload";
  const isAdmin = pathname.startsWith("/admin/");

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
      key: "logout",
      icon: <LogoutOutlined />,
      label: "退出登录",
      onClick: () => logout.mutate(undefined, { onSuccess: () => router.push("/") }),
    },
  ];

  const account = session.isLoading ? (
    <Skeleton.Avatar active size="small" />
  ) : session.data ? (
    <Dropdown menu={{ items: menuItems }} placement="bottomRight" trigger={["click"]}>
      <Button className="account-button" type="text">
        <Avatar size={30}>{session.data.avatarText}</Avatar>
        <span>{session.data.displayName}</span>
        <DownOutlined />
      </Button>
    </Dropdown>
  ) : (
    <Button type="primary" href="/login">
      登录
    </Button>
  );

  if (isWorkspace || isAdmin) {
    const navigation = isAdmin
      ? [{ href: "/admin/reviews", label: "公开审核", icon: <AuditOutlined /> }]
      : workspaceNavigation;

    return (
      <div className="app-frame workspace-frame">
        <aside className="workspace-sidebar">
          <Link className="brand-link" href="/" aria-label="茶杯图库首页">
            <span className="brand-mark">茶</span>
            <span>{isAdmin ? "管理后台" : "茶杯图库"}</span>
          </Link>
          <p className="sidebar-section-label">{isAdmin ? "PLATFORM" : "WORKSPACE"}</p>
          <nav className="sidebar-nav" aria-label={isAdmin ? "管理导航" : "工作台导航"}>
            {navigation.map((item) => (
              <Link
                className={pathname === item.href ? "sidebar-link is-active" : "sidebar-link"}
                href={item.href}
                key={item.href}
              >
                {item.icon}
                <span>{item.label}</span>
              </Link>
            ))}
            {!isAdmin && session.data?.role === "admin" ? (
              <Link className="sidebar-link" href="/admin/reviews">
                <AuditOutlined />
                <span>审核管理</span>
              </Link>
            ) : null}
          </nav>
          <div className="sidebar-account">{account}</div>
        </aside>
        <div className="workspace-main">
          <header className="workspace-header">
            <span>
              {isAdmin
                ? "管理后台 / 图片公开审核"
                : pathname === "/upload"
                  ? "个人空间 / 上传图片"
                  : "个人空间 / 空间概览"}
            </span>
          </header>
          <div className="workspace-page">{children}</div>
        </div>
      </div>
    );
  }

  return (
    <div className="app-frame">
      <header className="global-header">
        <div className="header-inner">
          <Link className="brand-link" href="/" aria-label="茶杯图库首页">
            <span className="brand-mark">茶</span>
            <span>茶杯图库</span>
          </Link>
          <nav className="primary-nav" aria-label="主导航">
            {publicNavigation.map((item) => (
              <Link
                className={pathname === item.href ? "nav-link is-active" : "nav-link"}
                href={item.href}
                key={item.href}
              >
                {item.icon}
                <span>{item.label}</span>
              </Link>
            ))}
          </nav>
          <div className="header-account">
            {session.data?.role === "user" ? (
              <Button type="primary" href="/upload" icon={<UploadOutlined />}>
                上传图片
              </Button>
            ) : null}
            <div>{account}</div>
          </div>
        </div>
      </header>
      {children}
    </div>
  );
}
