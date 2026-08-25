"use client";

import { CheckCircleOutlined, CommentOutlined, DeleteOutlined, EnvironmentOutlined, ReloadOutlined, SendOutlined } from "@ant-design/icons";
import { Alert, App, Avatar, Button, Empty, Input, List, Segmented, Select, Space, Spin, Tag, Tooltip } from "antd";
import { useEffect, useMemo, useRef, useState } from "react";
import { useCommentActions, useMentionCandidates } from "../queries";
import type { CommentItem, CommentPage } from "../model/types";

interface Props {
  pictureId: string;
  currentVersionId?: string;
  comments?: CommentPage;
  loading: boolean;
  error: boolean;
  focusError?: boolean;
  focusedCommentId?: string;
  authenticated: boolean;
  refreshKey: readonly unknown[];
  pendingAnchor?: { x: number; y: number } | null;
  onRequestAnchor: () => void;
  onClearAnchor: () => void;
  onLoadMore?: () => void;
  loadingMore?: boolean;
}

export function CommentPanel(props: Props) {
  const { message } = App.useApp();
  const [mode, setMode] = useState<"comment" | "annotation">("comment");
  const [body, setBody] = useState("");
  const [mentionedUserIds, setMentionedUserIds] = useState<string[]>([]);
  const [replying, setReplying] = useState<string | null>(null);
  const [replyBody, setReplyBody] = useState("");
  const [replyMentionedUserIds, setReplyMentionedUserIds] = useState<string[]>([]);
  const actions = useCommentActions(props.refreshKey);
  const candidates = useMentionCandidates(props.pictureId, props.authenticated);
  const items = useMemo(() => props.comments?.items ?? [], [props.comments]);
  const focusedOnce = useRef<string | null>(null);

  useEffect(() => {
    if (!props.focusedCommentId || focusedOnce.current === props.focusedCommentId) return;
    const target = document.getElementById(`discussion-${props.focusedCommentId}`);
    if (!target) return;
    focusedOnce.current = props.focusedCommentId;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    requestAnimationFrame(() => {
      target.scrollIntoView({ behavior: reducedMotion ? "auto" : "smooth", block: "center" });
      target.focus({ preventScroll: true });
    });
  }, [items, props.focusedCommentId]);

  const submit = () => {
    if (!body.trim()) return;
    if (mode === "annotation" && !props.pendingAnchor) {
      void message.warning("请先在图片上选择批注位置");
      return;
    }
    actions.create.mutate({
      pictureId: props.pictureId,
      kind: mode,
      body: body.trim(),
      pictureVersionId: mode === "annotation" ? props.currentVersionId : undefined,
      x: props.pendingAnchor?.x,
      y: props.pendingAnchor?.y,
      mentionedUserIds,
    }, {
      onSuccess: () => { setBody(""); setMentionedUserIds([]); props.onClearAnchor(); void message.success(mode === "annotation" ? "批注已添加" : "评论已发表"); },
      onError: (error) => void message.error(error.message),
    });
  };

  return <section className="discussion-panel" aria-label="评论与批注">
    <div className="discussion-heading"><div><h2>讨论</h2><span>{items.length ? `${items.length} 个讨论串` : "暂无讨论"}</span></div></div>
    {props.focusError ? <Alert type="warning" showIcon message="目标讨论不存在或当前账号无权查看" /> : null}
    {props.authenticated ? <div className="comment-composer">
      <div className="comment-composer-toolbar"><Segmented value={mode} onChange={(value) => { setMode(value as typeof mode); if (value === "comment") props.onClearAnchor(); }} options={[{ label: "评论", value: "comment", icon: <CommentOutlined /> }, { label: "位置批注", value: "annotation", icon: <EnvironmentOutlined /> }]} />
      {mode === "annotation" ? <Button icon={<EnvironmentOutlined />} type={props.pendingAnchor ? "primary" : "default"} onClick={props.onRequestAnchor}>{props.pendingAnchor ? "已选择位置" : "在图片上选择位置"}</Button> : null}</div>
      <Input.TextArea aria-label="评论内容" maxLength={2000} showCount autoSize={{ minRows: 3, maxRows: 7 }} placeholder={mode === "annotation" ? "描述这个位置需要处理的问题" : "写下你的想法"} value={body} onChange={(event) => setBody(event.target.value)} />
      <MentionSelect value={mentionedUserIds} onChange={setMentionedUserIds} loading={candidates.isLoading} candidates={candidates.data ?? []} />
      <Button type="primary" icon={<SendOutlined />} loading={actions.create.isPending} disabled={!body.trim()} onClick={submit}>发表</Button>
    </div> : <Alert type="info" showIcon message="登录后可发表评论、回复和批注" />}
    {props.loading ? <div className="discussion-loading"><Spin /></div> : props.error ? <Alert type="error" showIcon message="评论加载失败" /> : items.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有评论" /> : <List className="comment-thread-list" dataSource={items} renderItem={(item) => <ThreadItem key={item.id} item={item} focusedCommentId={props.focusedCommentId} currentVersionId={props.currentVersionId} authenticated={props.authenticated} replying={replying} replyBody={replyBody} replyMentionedUserIds={replyMentionedUserIds} setReplying={setReplying} setReplyBody={setReplyBody} setReplyMentionedUserIds={setReplyMentionedUserIds} candidates={candidates.data ?? []} candidatesLoading={candidates.isLoading} actions={actions} />} />}
    {props.comments?.hasMore && props.onLoadMore ? <Button block loading={props.loadingMore} onClick={props.onLoadMore}>加载更多讨论</Button> : null}
  </section>;
}

function ThreadItem({ item, focusedCommentId, currentVersionId, authenticated, replying, replyBody, replyMentionedUserIds, setReplying, setReplyBody, setReplyMentionedUserIds, candidates, candidatesLoading, actions }: {
  item: CommentItem; focusedCommentId?: string; currentVersionId?: string; authenticated: boolean; replying: string | null; replyBody: string;
  replyMentionedUserIds: string[]; setReplying: (id: string | null) => void; setReplyBody: (value: string) => void;
  setReplyMentionedUserIds: (value: string[]) => void; candidates: Array<{ id: string; name: string; avatarUrl?: string | null }>;
  candidatesLoading: boolean;
  actions: ReturnType<typeof useCommentActions>;
}) {
  const isHistorical = item.kind === "annotation" && item.pictureVersionId !== currentVersionId;
  const startReply = (replyToId: string) => {
    setReplying(replying === replyToId ? null : replyToId);
    setReplyBody("");
    setReplyMentionedUserIds([]);
  };
  const replyAuthors = new Map([[item.id, item.author.name], ...item.replies.map((reply) => [reply.id, reply.author.name] as const)]);
  return <List.Item id={`discussion-${item.id}`} tabIndex={-1} className={`${item.resolved ? "comment-thread is-resolved" : "comment-thread"}${focusedCommentId === item.id ? " is-notification-focus" : ""}`}>
    <div className="comment-thread-body">
      <div className="comment-author"><Avatar src={item.author.avatarUrl}>{item.author.name.slice(0, 1)}</Avatar><div><strong>{item.author.name}</strong><span>{new Date(item.createdAt).toLocaleString("zh-CN")}</span></div></div>
      <div className="comment-copy">{item.deleted ? <em>该评论已删除</em> : item.body}</div>
      <Space size={6} wrap>
        {item.kind === "annotation" ? <Tag icon={<EnvironmentOutlined />} color={isHistorical ? "default" : "blue"}>{isHistorical ? "历史版本批注" : "当前版本批注"}</Tag> : null}
        {item.resolved ? <Tag icon={<CheckCircleOutlined />} color="green">已解决</Tag> : null}
      </Space>
      <div className="comment-actions">
        {authenticated && !item.deleted ? <Button type="link" size="small" onClick={() => startReply(item.id)}>回复</Button> : null}
        {item.canResolve ? <Button type="link" size="small" icon={item.resolved ? <ReloadOutlined /> : <CheckCircleOutlined />} loading={actions.resolve.isPending} onClick={() => actions.resolve.mutate({ rootId: item.id, resolved: !item.resolved })}>{item.resolved ? "重新打开" : "解决"}</Button> : null}
        {item.canDelete ? <Tooltip title="删除评论"><Button aria-label="删除评论" danger type="text" size="small" icon={<DeleteOutlined />} onClick={() => actions.remove.mutate(item.id)} /></Tooltip> : null}
      </div>
      {replying === item.id ? <ReplyComposer rootId={item.id} replyToId={item.id} targetName={item.author.name} replyBody={replyBody} replyMentionedUserIds={replyMentionedUserIds} setReplying={setReplying} setReplyBody={setReplyBody} setReplyMentionedUserIds={setReplyMentionedUserIds} candidates={candidates} candidatesLoading={candidatesLoading} actions={actions} /> : null}
      {item.replies.length ? <div className="comment-replies">{item.replies.map((reply) => {
        const replyTargetName = reply.replyToId ? replyAuthors.get(reply.replyToId) : undefined;
        return <div className={`comment-reply-entry${focusedCommentId === reply.id ? " is-notification-focus" : ""}`} id={`discussion-${reply.id}`} tabIndex={-1} key={reply.id}>
          <div className="comment-reply">
            <Avatar size={24} src={reply.author.avatarUrl}>{reply.author.name.slice(0, 1)}</Avatar>
            <div>
              <div className="comment-reply-heading"><strong>{reply.author.name}</strong>{reply.replyToId !== item.id && replyTargetName ? <small>回复 {replyTargetName}</small> : null}</div>
              <span>{reply.deleted ? "该回复已删除" : reply.body}</span>
              <small>{new Date(reply.createdAt).toLocaleString("zh-CN")}</small>
            </div>
            <div className="comment-reply-actions">
              {authenticated && !reply.deleted ? <Button type="link" size="small" onClick={() => startReply(reply.id)}>回复</Button> : null}
              {reply.canDelete ? <Tooltip title="删除回复"><Button aria-label="删除回复" danger type="text" size="small" icon={<DeleteOutlined />} onClick={() => actions.remove.mutate(reply.id)} /></Tooltip> : null}
            </div>
          </div>
          {replying === reply.id ? <ReplyComposer rootId={item.id} replyToId={reply.id} targetName={reply.author.name} replyBody={replyBody} replyMentionedUserIds={replyMentionedUserIds} setReplying={setReplying} setReplyBody={setReplyBody} setReplyMentionedUserIds={setReplyMentionedUserIds} candidates={candidates} candidatesLoading={candidatesLoading} actions={actions} /> : null}
        </div>;
      })}</div> : null}
      {item.replyCount > item.replies.length ? <span className="reply-overflow">还有 {item.replyCount - item.replies.length} 条较早回复</span> : null}
    </div>
  </List.Item>;
}

function ReplyComposer({ rootId, replyToId, targetName, replyBody, replyMentionedUserIds, setReplying, setReplyBody, setReplyMentionedUserIds, candidates, candidatesLoading, actions }: {
  rootId: string; replyToId: string; targetName: string; replyBody: string; replyMentionedUserIds: string[];
  setReplying: (id: string | null) => void; setReplyBody: (value: string) => void;
  setReplyMentionedUserIds: (value: string[]) => void; candidates: Array<{ id: string; name: string; avatarUrl?: string | null }>;
  candidatesLoading: boolean; actions: ReturnType<typeof useCommentActions>;
}) {
  return <div className="reply-composer">
    <Input.TextArea aria-label="回复内容" placeholder={`回复 ${targetName}`} autoSize={{ minRows: 2, maxRows: 4 }} maxLength={2000} value={replyBody} onChange={(event) => setReplyBody(event.target.value)} />
    <MentionSelect value={replyMentionedUserIds} onChange={setReplyMentionedUserIds} loading={candidatesLoading} candidates={candidates} />
    <Button type="primary" size="small" loading={actions.reply.isPending} disabled={!replyBody.trim()} onClick={() => actions.reply.mutate({ rootId, replyToId, body: replyBody.trim(), mentionedUserIds: replyMentionedUserIds }, { onSuccess: () => { setReplying(null); setReplyBody(""); setReplyMentionedUserIds([]); } })}>回复</Button>
  </div>;
}

function MentionSelect({ value, onChange, loading, candidates }: {
  value: string[];
  onChange: (value: string[]) => void;
  loading: boolean;
  candidates: Array<{ id: string; name: string; avatarUrl?: string | null }>;
}) {
  return <Select
    aria-label="提及用户"
    mode="multiple"
    allowClear
    maxTagCount="responsive"
    loading={loading}
    placeholder="提及相关成员（可选）"
    value={value}
    onChange={onChange}
    options={candidates.map((candidate) => ({
      value: candidate.id,
      label: <Space size={8}><Avatar size={20} src={candidate.avatarUrl}>{candidate.name.slice(0, 1)}</Avatar><span>{candidate.name}</span></Space>,
    }))}
    optionFilterProp="label"
  />;
}
