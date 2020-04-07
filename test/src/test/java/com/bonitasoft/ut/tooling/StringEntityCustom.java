package com.bonitasoft.ut.tooling;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.AbstractHttpEntity;
import org.apache.hc.core5.util.Args;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;


@Contract(threading = ThreadingBehavior.IMMUTABLE)
public class StringEntityCustom extends AbstractHttpEntity {

    private final byte[] content;

    /**
     * Creates a StringEntity with the specified content and content type.
     *
     * @param string      content to be used. Not {@code null}.
     * @param contentType content type to be used. May be {@code null}, in which case the default
     *                    MIME type {@link ContentType#TEXT_PLAIN} is assumed.
     * @since 5.0
     */
    public StringEntityCustom(
            final String string, final ContentType contentType, final String contentEncoding, final boolean chunked) {
        super(contentType, contentEncoding, chunked);
        Args.notNull(string, "Source string");

        this.content = string.getBytes();
    }

    public StringEntityCustom(final String string, final ContentType contentType, final boolean chunked) {
        this(string, contentType, null, chunked);
    }

    public StringEntityCustom(final String string, final ContentType contentType) {
        this(string, contentType, null, false);
    }

    /**
     * Creates a StringEntity with the specified content and charset. The MIME type defaults
     * to "text/plain".
     *
     * @param string  content to be used. Not {@code null}.
     * @param charset character set to be used. May be {@code null}, in which case the default
     *                is {@link StandardCharsets#ISO_8859_1} is assumed
     * @since 4.2
     */
    public StringEntityCustom(final String string, final Charset charset) {
        this(string, ContentType.TEXT_PLAIN.withCharset(charset));
    }

    public StringEntityCustom(final String string, final Charset charset, final boolean chunked) {
        this(string, ContentType.TEXT_PLAIN.withCharset(charset), chunked);
    }

    /**
     * Creates a StringEntity with the specified content. The content type defaults to
     * {@link ContentType#TEXT_PLAIN}.
     *
     * @param string content to be used. Not {@code null}.
     * @throws IllegalArgumentException if the string parameter is null
     */
    public StringEntityCustom(final String string) {
        this(string, ContentType.DEFAULT_TEXT);
    }

    @Override
    public final boolean isRepeatable() {
        return true;
    }

    @Override
    public final long getContentLength() {
        return this.content.length;
    }

    @Override
    public final InputStream getContent() throws IOException {
        return new ByteArrayInputStream(this.content);
    }

    @Override
    public final void writeTo(final OutputStream outStream) throws IOException {
        Args.notNull(outStream, "Output stream");
        outStream.write(this.content);
        outStream.flush();
    }

    @Override
    public final boolean isStreaming() {
        return false;
    }

    @Override
    public final void close() throws IOException {
        // nothing to do
    }

}
