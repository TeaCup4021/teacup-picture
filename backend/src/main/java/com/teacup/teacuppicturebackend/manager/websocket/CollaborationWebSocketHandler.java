package com.teacup.teacuppicturebackend.manager.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teacup.teacuppicturebackend.api.v1.CollaborationService;
import com.teacup.teacuppicturebackend.api.v1.V1Exception;
import com.teacup.teacuppicturebackend.api.v1.model.CollaborationDtos;
import com.teacup.teacuppicturebackend.model.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class CollaborationWebSocketHandler extends TextWebSocketHandler {
    private static final int MAX_MESSAGE_CHARS = 400_000;

    @Resource
    private CollaborationService collaboration;

    @Resource
    private ObjectMapper objectMapper;

    private final Map<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (message.getPayloadLength() > MAX_MESSAGE_CHARS) {
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        JsonNode node;
        try { node = objectMapper.readTree(message.getPayload()); }
        catch (Exception exception) {
            sendError(session, "消息格式无效");
            return;
        }
        String type = text(node, "type");
        if ("hello".equals(type)) { handleHello(session, node); return; }
        String roomId = (String) session.getAttributes().get("collaborationRoomId");
        if (roomId == null) { sendError(session, "尚未加入协作房间"); return; }
        User user = (User) session.getAttributes().get("user");
        long pictureId = (Long) session.getAttributes().get("pictureId");
        if ("update".equals(type)) { handleUpdate(session, user, pictureId, roomId, node); return; }
        if ("awareness".equals(type)) { handleAwareness(session, roomId, node); return; }
        if ("ping".equals(type)) { send(session, objectMapper.createObjectNode().put("type", "pong")); return; }
        sendError(session, "不支持的协作消息");
    }

    private void handleHello(WebSocketSession session, JsonNode node) throws IOException {
        if (session.getAttributes().get("collaborationRoomId") != null) return;
        User user = (User) session.getAttributes().get("user");
        long pictureId = (Long) session.getAttributes().get("pictureId");
        CollaborationDtos.Session room = collaboration.getSession(user, pictureId);
        if (!room.enabled()) { close(session, CloseStatus.POLICY_VIOLATION); return; }
        String requestedEpoch = text(node, "roomEpoch");
        if (!room.roomEpoch().equals(requestedEpoch)) {
            sendError(session, "协作房间已切换，请刷新页面");
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        long afterSeq = parseLong(text(node, "lastServerSeq"), 0);
        var records = collaboration.updatesAfter(user, pictureId, requestedEpoch, afterSeq);
        session.getAttributes().put("collaborationRoomId", room.roomId());
        roomSessions.computeIfAbsent(room.roomId(), ignored -> ConcurrentHashMap.newKeySet()).add(session);

        ObjectNode welcome = objectMapper.createObjectNode();
        welcome.put("type", "welcome");
        welcome.put("roomId", room.roomId());
        welcome.put("roomEpoch", room.roomEpoch());
        welcome.put("serverSeq", room.lastServerSeq());
        welcome.put("canEdit", room.canEdit());
        ArrayNode updates = welcome.putArray("updates");
        records.forEach(record -> updates.add(recordNode(record, "update")));
        send(session, welcome);
        broadcast(room.roomId(), objectMapper.createObjectNode().put("type", "presence").put("event", "joined"), session);
    }

    private void handleUpdate(WebSocketSession session, User user, long pictureId, String roomId,
                              JsonNode node) throws IOException {
        CollaborationDtos.UpdateRequest request;
        try { request = objectMapper.treeToValue(node, CollaborationDtos.UpdateRequest.class); }
        catch (Exception exception) { sendError(session, "协作更新格式无效"); return; }
        CollaborationDtos.UpdateResult result;
        try { result = collaboration.append(user, pictureId, request); }
        catch (V1Exception exception) { sendError(session, exception.getMessage()); return; }
        ObjectNode ack = objectMapper.createObjectNode();
        ack.put("type", "ack");
        ack.put("operationId", result.record().operationId());
        ack.put("serverSeq", result.record().serverSeq());
        ack.put("duplicate", result.duplicate());
        send(session, ack);
        if (!result.duplicate()) broadcast(roomId, recordNode(result.record(), "update"), session);
    }

    private void handleAwareness(WebSocketSession session, String roomId, JsonNode node) throws IOException {
        JsonNode payload = node.get("payload");
        if (payload == null || payload.toString().length() > 32_000) {
            sendError(session, "在线状态过大");
            return;
        }
        ObjectNode message = objectMapper.createObjectNode();
        message.put("type", "awareness");
        message.set("payload", payload);
        message.put("actorId", String.valueOf(session.getAttributes().get("userId")));
        broadcast(roomId, message, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String roomId = (String) session.getAttributes().get("collaborationRoomId");
        if (roomId == null) return;
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            broadcast(roomId, objectMapper.createObjectNode().put("type", "presence").put("event", "left")
                    .put("actorId", String.valueOf(session.getAttributes().get("userId"))), session);
            if (sessions.isEmpty()) roomSessions.remove(roomId, sessions);
        }
    }

    private void broadcast(String roomId, ObjectNode message, WebSocketSession excluded) throws IOException {
        Set<WebSocketSession> sessions = roomSessions.getOrDefault(roomId, Collections.emptySet());
        TextMessage text = new TextMessage(objectMapper.writeValueAsString(message));
        for (WebSocketSession target : sessions) {
            if (target == excluded || !target.isOpen()) continue;
            synchronized (target) { target.sendMessage(text); }
        }
    }

    private void send(WebSocketSession session, ObjectNode message) throws IOException {
        if (session.isOpen()) {
            synchronized (session) { session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message))); }
        }
    }

    private void sendError(WebSocketSession session, String message) throws IOException {
        send(session, objectMapper.createObjectNode().put("type", "error").put("message", message));
    }

    private static ObjectNode recordNode(CollaborationDtos.UpdateRecord record, String type) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("operationId", record.operationId());
        node.put("gestureId", record.gestureId());
        node.put("kind", record.kind());
        node.put("targetId", record.targetId());
        node.set("changedFields", mapper.valueToTree(record.changedFields()));
        node.put("phase", record.phase());
        node.put("yjsUpdate", record.yjsUpdate());
        node.put("serverSeq", record.serverSeq());
        node.put("actorId", record.actorId());
        node.put("createdAt", record.createdAt().toString());
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Long.parseLong(value); } catch (NumberFormatException exception) { return fallback; }
    }

    private static void close(WebSocketSession session, CloseStatus status) throws IOException {
        if (session.isOpen()) session.close(status);
    }
}
