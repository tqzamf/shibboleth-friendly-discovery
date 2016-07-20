package de.uniKonstanz.shib.disco.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.Date;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.DateUtils;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.LaxRedirectStrategy;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Wrapper around {@link HttpClient}, with sensible timeouts for every
 * operation. Unlike {@link URLConnection}, this cannot hang forever.
 */
public class HTTP {
	/** Time until the request has to be completed: 1 minute. */
	private static final long TIMEOUT = 60 * 1000;
	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static final RequestConfig config;
	private static final CloseableHttpClient client;
	private static final ObjectMapper oma;
	private static final DocumentBuilder docBuilder;

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
		final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		try {
			docBuilder = dbf.newDocumentBuilder();
		} catch (final ParserConfigurationException e) {
			throw new RuntimeException(e);
		}
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

	/** Encoding helper; uses declared encoding or defaults to UTF-8. */
	private static Charset getContentEncoding(final HttpEntity entity) {
		final Header encoding = entity.getContentEncoding();
		if (encoding != null && encoding.getValue() != null)
			return Charset.forName(encoding.getValue());
		return UTF8;
	}

	/**
	 * Reads XML from a URL and parses it into a DOM tree. There is no limit on
	 * the size of the input.
	 * 
	 * @param url
	 *            the URL to read
	 * @param lastModified
	 *            timestamp of last download, or <code>null</code> to download
	 *            the file unconditionally
	 * @return an XML {@link Document} containing the parsed DOM tree, or
	 *         <code>null</code> if it wasn't modified
	 * @throws IOException
	 *             on IO errors
	 */
	public static Document getXML(final String url, final Date lastModified)
			throws IOException {
		final HttpGet req = getRequest(url, lastModified);
		try {
			final HttpEntity entity = performRequest(req);
			if (entity == null)
				return null; // not modified
			return docBuilder.parse(entity.getContent());
		} catch (final SAXException e) {
			throw new IOException("parsing failed", e);
		} finally {
			// make sure request can be reused
			req.reset();
		}
	}

	/**
	 * Reads a URL into a {@code byte[]}, throwing an {@link IOException} if the
	 * entity is larger than {@code maxSize} bytes.
	 * 
	 * @param url
	 *            the URL to read
	 * @param maxSize
	 *            maximum number of bytes to read
	 * @param lastModified
	 *            value to send in <code>If-Modified-Since</code> header to
	 *            avoid repeatedly re-fetching files that haven't changed, or
	 *            <code>null</code> to omit that header and unconditionally
	 *            download the file
	 * @return all bytes from the URL, or <code>null</code> if not modified
	 * @throws IOException
	 *             if the URL returned more than {@code maxSize} bytes, isn't
	 *             valid in the first place, and on IO errors
	 */
	public static byte[] getBytes(final String url, final int maxSize,
			final Date lastModified) throws IOException {
		final HttpGet req = getRequest(url, lastModified);
		try {
			final HttpEntity entity = performRequest(req);
			if (entity == null)
				return null; // not modified
			return readBytes(entity, maxSize);
		} finally {
			// make sure we don't leak connections
			req.reset();
		}
	}

	/**
	 * Builds an {@link HttpGet} request, optionally with an
	 * {@code If-Modified-Since} header.
	 */
	private static HttpGet getRequest(final String url, final Date lastModified)
			throws IOException {
		final HttpGet req;
		try {
			req = new HttpGet(url);
			if (lastModified != null)
				req.setHeader("If-Modified-Since",
						DateUtils.formatDate(lastModified));
		} catch (final IllegalArgumentException e) {
			throw new IOException("URL not valid", e);
		}
		return req;
	}

	/** Request helper; throws {@link IOException} on non-200 response. */
	private static HttpEntity performRequest(final HttpGet req)
			throws IOException {
		// perform HTTP request
		final HttpResponse resp = client.execute(req);
		if (resp.getStatusLine().getStatusCode() == HttpStatus.SC_NOT_MODIFIED)
			return null;
		if (resp.getStatusLine().getStatusCode() != HttpStatus.SC_OK)
			throw new IOException("unexpected response: "
					+ resp.getStatusLine());
		return resp.getEntity();
	}

	/**
	 * Reads an {@link HttpEntity} into a {@code byte[]}, throwing an
	 * {@link IOException} if the entity is larger than {@code maxSize} bytes or
	 * the request takes longer than {@link #TIMEOUT} to complete.
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
		try (final InputStream in = entity.getContent()) {
			length = readBytes(in, buffer);
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
	 * contains more bytes than fit into the buffer or the request takes longer
	 * than {@link #TIMEOUT} to complete. The entire stream will be read, but
	 * not closed.
	 * 
	 * @param in
	 *            {@link InputStream} to read
	 * @param buffer
	 *            buffer to write data to
	 * @return number of bytes read
	 * @throws IOException
	 *             if {@code in} contains more bytes than fit into
	 *             {@code buffer}, if reading the request takes too long, and on
	 *             IO errors
	 */
	private static int readBytes(final InputStream in, final byte[] buffer)
			throws IOException {
		final long timeout = System.currentTimeMillis() + TIMEOUT;
		int offset = 0;
		while (offset < buffer.length) {
			final int len = in.read(buffer, offset, buffer.length - offset);
			if (len <= 0)
				// EOF before end of buffer, ie. no overflow
				return offset;
			final long now = System.currentTimeMillis();
			if (now > timeout)
				throw new IOException("read timed out: " + (now - timeout));
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
