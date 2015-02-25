package de.uniKonstanz.shib.disco.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;

public class HTTP {
	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static final RequestConfig config;
	private static final CloseableHttpClient client;
	private static final ObjectMapper oma;

	static {
		config = RequestConfig.custom()
		// don't let connection sit idle forever
				.setConnectTimeout(15000).setSocketTimeout(15000)
				// don't go into redirect loops
				.setMaxRedirects(10).build();
		client = HttpClientBuilder
				.create()
				.setDefaultRequestConfig(config)
				// retry a few times, re-sending any data already sent because
				// we actually need the response
				.setRetryHandler(new DefaultHttpRequestRetryHandler(3, true))
				// follow all redirections
				.setRedirectStrategy(new LaxRedirectStrategy())
				// don't reuse connections, ever. requests only happen every few
				// minutes, which is too long for keepalive. if they ever do
				// happen more frequently, that's because of errors, and then
				// they cannot be reused anyway.
				.setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE)
				.setKeepAliveStrategy(new NoKeepAliveStrategy()).build();
		oma = new ObjectMapper();
	}

	/**
	 * Reads JSON from a URL and parses it into a Java object. There is no limit
	 * on the size of the JSON input.
	 * 
	 * @param <T>
	 *            type of the returned Java object
	 * @param url
	 *            the URL to read
	 * @param type
	 *            a Jackson {@link TypeReference} describing the object to
	 *            return
	 * @return a Java object converted from JSON
	 * @throws IOException
	 *             on IO errors
	 */
	public static <T> T getJSON(final String url, final TypeReference<T> type)
			throws IOException {
		final HttpGet req = new HttpGet(url);
		try {
			final HttpEntity entity = performRequest(req);
			final InputStreamReader reader = new InputStreamReader(
					entity.getContent(), getContentEncoding(entity));
			return oma.readValue(reader, type);
		} finally {
			// make sure request can be reused
			req.reset();
		}
	}

	private static Charset getContentEncoding(final HttpEntity entity) {
		final Header encoding = entity.getContentEncoding();
		if (encoding != null && encoding.getValue() != null)
			return Charset.forName(encoding.getValue());
		return UTF8;
	}

	//
	// public static String getString(final String url, final int maxSize)
	// throws IOException {
	// final HttpGet req = new HttpGet(url);
	// try {
	// final HttpEntity entity = performRequest(req);
	// final byte[] buffer = readBytes(entity, maxSize);
	//
	// // convert to string, using the character set provided by the source
	// // if possible. fall back to UTF-8 otherwise.
	// final Header encoding = entity.getContentEncoding();
	// final Charset charset;
	// if (encoding != null && encoding.getValue() != null)
	// charset = Charset.forName(encoding.getValue());
	// else
	// charset = UTF8;
	// return new String(buffer, charset);
	// } finally {
	// // make sure request can be reused
	// req.reset();
	// }
	// }

	/**
	 * Reads a URL into a {@code byte[]}, throwing an {@link IOException} if the
	 * entity is larger than {@code maxSize} bytes.
	 * 
	 * @param url
	 *            the URL to read
	 * @param maxSize
	 *            maximum number of bytes to read
	 * @return all bytes from the URL
	 * @throws IOException
	 *             if the URL returned more than {@code maxSize} bytes, and on
	 *             IO errors
	 */
	public static byte[] getBytes(final String url, final int maxSize)
			throws IOException {
		final HttpGet req = new HttpGet(url);
		try {
			final HttpEntity entity = performRequest(req);
			return readBytes(entity, maxSize);
		} finally {
			// make sure we don't leak connections
			req.reset();
		}
	}

	private static HttpEntity performRequest(final HttpGet req)
			throws IOException {
		// perform HTTP request
		final HttpResponse resp = client.execute(req);
		if (resp.getStatusLine().getStatusCode() != HttpStatus.SC_OK)
			throw new IOException("unexpected response: "
					+ resp.getStatusLine());
		return resp.getEntity();
	}

	/**
	 * Reads an {@link HttpEntity} into a {@code byte[]}, throwing an
	 * {@link IOException} if the entity is larger than {@code maxSize} bytes.
	 * 
	 * @param entity
	 *            {@link HttpEntity} to read into memory
	 * @param maxSize
	 *            maximum number of bytes to read
	 * @return all bytes from {@code entity}
	 * @throws IOException
	 *             if {@code entity} is larger than {@code maxSize} bytes, and
	 *             on IO errors
	 */
	private static byte[] readBytes(final HttpEntity entity, final int maxSize)
			throws IOException {
		// quick check for content known to be too large
		final long responseLength = entity.getContentLength();
		final int contentLength;
		if (responseLength < 0)
			contentLength = maxSize;
		else if (responseLength <= maxSize)
			contentLength = (int) responseLength;
		else
			throw new IOException("response too large: " + responseLength);

		// read response, carefully checking that it doesn't overflow the
		// maximum size
		final int length;
		final byte[] buffer = new byte[contentLength];
		final InputStream in = entity.getContent();
		try {
			length = readBytes(in, buffer);
		} finally {
			in.close();
		}

		// if a Content-Length was specified, it should exactly match the
		// length of the response here. otherwise, a new array having the
		// proper size has to be allocated.
		if (length == contentLength)
			return buffer;
		final byte[] res = new byte[length];
		System.arraycopy(buffer, 0, res, 0, length);
		return res;
	}

	/**
	 * Reads bytes until EOF, throwing an {@link IOException} if the stream
	 * contains more bytes than fit into the buffer. The entire stream will be
	 * read, but not closed.
	 * 
	 * @param in
	 *            {@link InputStream} to read
	 * @param buffer
	 *            buffer to write data to
	 * @return number of bytes read
	 * @throws IOException
	 *             if {@code in} contains more bytes than fit into
	 *             {@code buffer}, and on IO errors
	 */
	private static int readBytes(final InputStream in, final byte[] buffer)
			throws IOException {
		int offset = 0;
		while (offset < buffer.length) {
			final int len = in.read(buffer, offset, buffer.length - offset);
			if (len <= 0)
				// EOF before end of buffer, ie. no overflow
				return offset;
			offset += len;
		}

		// reached end of buffer. that's ok only if the stream is at EOF as
		// well.
		if (in.read() == -1)
			return offset;
		// stream has more bytes than fit into the buffer
		throw new IOException("response too large: " + offset);
	}
}
