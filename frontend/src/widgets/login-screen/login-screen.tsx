"use client";

import { LockOutlined, UserOutlined } from "@ant-design/icons";
import { Alert, Button, Form, Input } from "antd";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { usePrototypeLogin, usePrototypeSession } from "@/features/prototype";
import type { LoginInput } from "@/features/prototype";

export function LoginScreen() {
  const [form] = Form.useForm<LoginInput>();
  const router = useRouter();
  const session = usePrototypeSession();
  const login = usePrototypeLogin();

  useEffect(() => {
    if (session.data) {
      router.replace(session.data.role === "admin" ? "/admin/reviews" : "/spaces/personal");
    }
  }, [router, session.data]);

  const fillAccount = (account: "muyi" | "admin") => {
    form.setFieldsValue({
      account,
      password: account === "admin" ? "admin123" : "demo123",
    });
  };

  const handleSubmit = (values: LoginInput) => {
    login.mutate(values, {
      onSuccess: (user) => {
        router.replace(user.role === "admin" ? "/admin/reviews" : "/spaces/personal");
      },
    });
  };

  return (
    <main className="login-page">
      <section className="login-visual" aria-label="茶杯图库精选图片">
        <Image alt="城市日落" fill loading="eager" sizes="50vw" src="/mock-images/gallery-05.jpg" />
        <div className="login-visual-caption">
          <span className="brand-mark">茶</span>
          <strong>茶杯图库</strong>
          <p>保存、审核与发现图片作品</p>
        </div>
      </section>
      <section className="login-panel" aria-labelledby="login-title">
        <div className="login-form-wrap">
          <div className="login-heading">
            <p className="page-kicker">WELCOME BACK</p>
            <h1 id="login-title">登录</h1>
            <p>进入你的个人空间</p>
          </div>
          <div className="demo-account-row" aria-label="体验账号">
            <Button onClick={() => fillAccount("muyi")}>普通用户</Button>
            <Button onClick={() => fillAccount("admin")}>管理员</Button>
          </div>
          {login.error ? (
            <Alert type="error" showIcon title={login.error.message} className="form-alert" />
          ) : null}
          <Form
            form={form}
            layout="vertical"
            initialValues={{ account: "muyi", password: "demo123" }}
            onFinish={handleSubmit}
            requiredMark={false}
          >
            <Form.Item
              label="账号"
              name="account"
              rules={[{ required: true, message: "请输入账号" }]}
            >
              <Input prefix={<UserOutlined />} autoComplete="username" placeholder="请输入账号" />
            </Form.Item>
            <Form.Item
              label="密码"
              name="password"
              rules={[{ required: true, message: "请输入密码" }]}
            >
              <Input.Password
                prefix={<LockOutlined />}
                autoComplete="current-password"
                placeholder="请输入密码"
              />
            </Form.Item>
            <Button block type="primary" htmlType="submit" loading={login.isPending}>
              登录
            </Button>
          </Form>
          <Button type="link" href="/" className="back-to-gallery">
            返回公开图库
          </Button>
        </div>
      </section>
    </main>
  );
}
