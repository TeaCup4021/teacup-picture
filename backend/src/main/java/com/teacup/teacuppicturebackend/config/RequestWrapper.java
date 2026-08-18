package com.teacup.teacuppicturebackend.config;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * HttpServletRequest包装类，用于缓存请求体内容
 */
public class RequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;
    private final Charset charset;

    public RequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        body = request.getInputStream().readAllBytes();
        charset = request.getCharacterEncoding() == null
                ? StandardCharsets.UTF_8
                : Charset.forName(request.getCharacterEncoding());
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return byteArrayInputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
            }

            @Override
            public int read() throws IOException {
                return byteArrayInputStream.read();
            }
        };

    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(this.getInputStream(), charset));
    }

    public String getBody() {
        return new String(body, charset);
    }

}
