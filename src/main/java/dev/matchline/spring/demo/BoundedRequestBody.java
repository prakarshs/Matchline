package dev.matchline.spring.demo;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Small synchronous servlet request wrapper for an already bounded body.
 * Prevents unknown/chunked lengths from bypassing the ingress body limit.
 * Never buffers more than the configured limit + 1 byte.
 */
final class BoundedRequestBody extends HttpServletRequestWrapper {
    private final byte[] content;

    BoundedRequestBody(HttpServletRequest original, byte[] content) {
        super(original);
        this.content = content.clone();
    }

    @Override public int getContentLength() { return content.length; }
    @Override public long getContentLengthLong() { return content.length; }

    @Override public ServletInputStream getInputStream() {
        ByteArrayInputStream stream = new ByteArrayInputStream(content);
        return new ServletInputStream() {
            @Override public boolean isFinished() { return stream.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("asynchronous request reading is not supported");
            }
            @Override public int read() { return stream.read(); }
            @Override public int read(byte[] b, int off, int len) { return stream.read(b, off, len); }
        };
    }

    @Override public BufferedReader getReader() throws IOException {
        String encoding = getCharacterEncoding();
        return new BufferedReader(new InputStreamReader(getInputStream(),
                encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
    }
}
