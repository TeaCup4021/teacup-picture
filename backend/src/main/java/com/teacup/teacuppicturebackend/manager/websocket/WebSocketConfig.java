package com.teacup.teacuppicturebackend.manager.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import javax.annotation.Resource;

/**
 * WebSocket 配置（定义连接）
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private PictureEditHandler pictureEditHandler;

    @Resource
    private WsHandshakeInterceptor wsHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(pictureEditHandler, "/ws/picture/edit")
                //注册一个 WebSocket 处理器
                //"/ws/picture/edit"：这是 WebSocket 的访问端点（URL）。客户端需要通过这个地址（例如 ws://localhost:8080/ws/picture/edit）来发起连接。
                .addInterceptors(wsHandshakeInterceptor)
                //添加一个握手拦截器
                .setAllowedOrigins("*");
                //设置允许跨域访问的源。 ◦ "*"：表示允许来自任何域名的请求连接该 WebSocket。 ◦ 原理：WebSocket 协议在握手阶段会发送一个 Origin 头，服务器通过配置此参数来决定是否接受该跨域连接。
    }
}