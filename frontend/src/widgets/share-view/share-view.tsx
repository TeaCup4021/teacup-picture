"use client";

import { DownloadOutlined, LockOutlined } from "@ant-design/icons";
import { Alert, App, Button, Input, Result, Skeleton, Space, Tag } from "antd";
import { useCallback, useEffect, useMemo, useState, type MouseEvent } from "react";
import Link from "next/link";
import { ApiError } from "@/api/client";
import { absoluteApiUrl, interactionKeys, interactionsApi, mergeFocusedThread, normalizeAnnotationPosition, useCommentThread, useShareComments, useSharedPicture } from "@/features/interactions";
import { clearShareLoginContinuation, rememberShareLoginContinuation } from "@/features/interactions/share-login-continuation";
import { CommentPanel } from "@/features/interactions/ui/comment-panel";
import { PictureImage } from "@/features/prototype/ui/picture-image";
import { usePrototypeSession } from "@/features/prototype";

export function ShareView({ publicId, focusedThreadId, focusedCommentId }: { publicId: string; focusedThreadId?: string; focusedCommentId?: string }) {
  const { message } = App.useApp(); const session = usePrototypeSession();
  const [granted, setGranted] = useState(false); const [secret, setSecret] = useState(""); const [password, setPassword] = useState(""); const [passwordRequired, setPasswordRequired] = useState(false); const [accessError, setAccessError] = useState<string | null>(null); const [checking, setChecking] = useState(true);
  const [imageError, setImageError] = useState(false);
  const [selectingAnchor, setSelectingAnchor] = useState(false); const [anchor, setAnchor] = useState<{ x: number; y: number } | null>(null);
  const picture = useSharedPicture(publicId, granted); const comments = useShareComments(publicId, granted);
  const focusedThread = useCommentThread(focusedThreadId, Boolean(granted && session.data));
  const visibleComments = useMemo(() => mergeFocusedThread(comments.data, focusedThread.data), [comments.data, focusedThread.data]);

  const verify = useCallback((value: string, accessPassword?: string) => {
    setChecking(true); setAccessError(null);
    interactionsApi.accessShare(publicId, value, accessPassword).then(() => { setGranted(true); setPasswordRequired(false); }).catch((error: unknown) => {
      if (error instanceof ApiError && error.code === 40102) setPasswordRequired(true);
      else setAccessError(error instanceof Error ? error.message : "分享链接无法访问");
    }).finally(() => setChecking(false));
  }, [publicId]);

  useEffect(() => {
    const value = window.location.hash.replace(/^#/, "");
    clearShareLoginContinuation(publicId);
    queueMicrotask(() => {
      setSecret(value);
      verify(value);
    });
  }, [publicId, verify]);

  if (checking && !passwordRequired) return <main className="content-shell share-loading"><Skeleton active paragraph={{ rows: 10 }} /></main>;
  if (accessError) return <Result status="404" title="分享不可用" subTitle={accessError} extra={<Button href="/">返回公开图库</Button>} />;
  if (passwordRequired && !granted) return <main className="content-shell share-access-shell"><section className="share-access-panel"><LockOutlined /><h1>此分享需要访问密码</h1><Input.Password aria-label="访问密码" value={password} onChange={(event) => setPassword(event.target.value)} onPressEnter={() => verify(secret, password)} placeholder="请输入分享密码" /><Button type="primary" loading={checking} disabled={!password} onClick={() => verify(secret, password)}>验证并查看</Button></section></main>;
  if (picture.isError) return <Result status="404" title="分享不可用" extra={<Button href="/">返回公开图库</Button>} />;
  if (picture.isLoading || !picture.data) return <main className="content-shell share-loading"><Skeleton active paragraph={{ rows: 10 }} /></main>;

  const item = picture.data;
  const placeAnchor = (event: MouseEvent<HTMLDivElement>) => { if (!selectingAnchor) return; const rect = event.currentTarget.getBoundingClientRect(); const border = Number.parseFloat(getComputedStyle(event.currentTarget).borderLeftWidth) || 0; setAnchor(normalizeAnnotationPosition(event.clientX, event.clientY, { left: rect.left, top: rect.top, width: rect.width, height: rect.height, border })); setSelectingAnchor(false); };
  const annotations = (visibleComments?.items ?? []).filter((comment) => comment.kind === "annotation" && !comment.deleted && comment.pictureVersionId === item.currentVersionId && comment.x != null && comment.y != null);
  const returnTo = `/shares/${publicId}`;
  return <main className="content-shell shared-picture-shell">
    <section className="shared-picture-heading"><div><p className="page-kicker">SHARED PICTURE</p><h1>{item.name}</h1><p>{item.introduction || "暂无描述"}</p></div><Space wrap>{item.tags.map((tag) => <Tag key={tag}>{tag}</Tag>)}</Space></section>
    <div className="shared-picture-layout">
      <section className="detail-media"><div className={selectingAnchor ? "detail-image-frame is-placing-annotation" : "detail-image-frame"} style={{ aspectRatio: `${item.width} / ${item.height}` }} onClick={placeAnchor}>{imageError ? <Result status="warning" title="图片加载失败" subTitle="请稍后刷新重试" /> : <PictureImage alt={item.name} priority fallbackSrc="" src={absoluteApiUrl(item.imageUrl)} onError={() => setImageError(true)} />}{!imageError ? annotations.map((comment, index) => <button id={`annotation-pin-${comment.id}`} className={`${comment.resolved ? "annotation-pin is-resolved" : "annotation-pin"}${comment.id === focusedThreadId ? " is-notification-focus" : ""}`} style={{ left: `${comment.x! * 100}%`, top: `${comment.y! * 100}%` }} key={comment.id} title={comment.body} aria-label={`批注 ${index + 1}：${comment.body}`}>{index + 1}</button>) : null}{anchor && !imageError ? <span className="annotation-pin is-pending" style={{ left: `${anchor.x * 100}%`, top: `${anchor.y * 100}%` }}>+</span> : null}</div></section>
      <aside className="shared-picture-info"><div className="detail-author-row"><span className="detail-avatar">{item.author.name.slice(0, 1)}</span><div><strong>{item.author.name}</strong><span>{item.width} × {item.height}</span></div></div>{session.data ? <Button icon={<DownloadOutlined />} onClick={() => interactionsApi.downloadShare(publicId, item.name).catch((error: Error) => void message.error(error.message))}>下载图片</Button> : <Alert type="info" showIcon message={<span><Link href={`/login?returnTo=${encodeURIComponent(returnTo)}`} onNavigate={() => rememberShareLoginContinuation(publicId, window.location.hash)}>登录</Link>后可以下载和参与讨论</span>} />}</aside>
    </div>
    <CommentPanel pictureId={item.id} currentVersionId={item.currentVersionId ?? undefined} comments={visibleComments} loading={comments.isLoading && !focusedThread.data} error={comments.isError} focusError={Boolean(focusedThreadId && focusedThread.isError)} focusedCommentId={focusedCommentId} authenticated={Boolean(session.data)} refreshKey={interactionKeys.comments(publicId, "share")} pendingAnchor={anchor} onRequestAnchor={() => setSelectingAnchor(true)} onClearAnchor={() => { setAnchor(null); setSelectingAnchor(false); }} onLoadMore={() => void comments.fetchNextPage()} loadingMore={comments.isFetchingNextPage} />
  </main>;
}
