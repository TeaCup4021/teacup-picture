"use client";

import { LockOutlined, UserOutlined } from "@ant-design/icons";
import { Alert, Button, Form, Input } from "antd";
import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { usePrototypeRegister } from "@/features/prototype";
import type { RegisterInput } from "@/features/prototype";

export function RegisterScreen() {
  const router = useRouter();
  const register = usePrototypeRegister();

  const handleSubmit = (values: RegisterInput) => {
    register.mutate(values, { onSuccess: () => router.replace("/login") });
  };

  return (
    <main className="login-page">
      <section className="login-visual" aria-label="茶杯图库精选图片">
        <Image alt="峡湾风景" fill loading="eager" sizes="50vw" src="/mock-images/gallery-01.jpg" />
        <div className="login-visual-caption">
          <span className="brand-mark">茶</span><strong>茶杯图库</strong><p>建立你的个人图片空间</p>
        </div>
      </section>
      <section className="login-panel" aria-labelledby="register-title">
        <div className="login-form-wrap">
          <div className="login-heading"><p className="page-kicker">CREATE ACCOUNT</p><h1 id="register-title">注册</h1><p>注册后自动创建个人空间</p></div>
          {register.error ? <Alert type="error" showIcon title={register.error.message} className="form-alert" /> : null}
          <Form<RegisterInput> layout="vertical" requiredMark={false} onFinish={handleSubmit}>
            <Form.Item label="账号" name="account" rules={[{ required: true, min: 4, max: 64, message: "账号长度为 4 到 64 位" }]}>
              <Input prefix={<UserOutlined />} autoComplete="username" />
            </Form.Item>
            <Form.Item label="密码" name="password" rules={[{ required: true, min: 8, max: 72, message: "密码长度为 8 到 72 位" }]}>
              <Input.Password prefix={<LockOutlined />} autoComplete="new-password" />
            </Form.Item>
            <Form.Item label="确认密码" name="passwordConfirmation" dependencies={["password"]} rules={[{ required: true, message: "请再次输入密码" }, ({ getFieldValue }) => ({ validator(_, value) { return !value || getFieldValue("password") === value ? Promise.resolve() : Promise.reject(new Error("两次输入的密码不一致")); } })]}>
              <Input.Password prefix={<LockOutlined />} autoComplete="new-password" />
            </Form.Item>
            <Button block type="primary" htmlType="submit" loading={register.isPending}>创建账号</Button>
          </Form>
          <p className="auth-switch">已有账号？ <Link href="/login">返回登录</Link></p>
          <Button type="link" href="/" className="back-to-gallery">返回公开图库</Button>
        </div>
      </section>
    </main>
  );
}
