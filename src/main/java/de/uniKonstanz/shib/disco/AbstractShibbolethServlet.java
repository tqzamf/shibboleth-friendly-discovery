package de.uniKonstanz.shib.disco;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.io.ByteStreams;
import com.google.common.net.InetAddresses;

import de.uniKonstanz.shib.disco.loginlogger.LoginParams;
import de.uniKonstanz.shib.disco.metadata.MetadataUpdateThread;
import de.uniKonstanz.shib.disco.util.ConnectionPool;

/**
 * Methods common to all servlets.
 */
@SuppressWarnings("serial")
public abstract class AbstractShibbolethServlet extends HttpServlet {
	private static final int ONE_YEAR = (int) TimeUnit.DAYS.toSeconds(365);
	private static final Logger LOGGER = Logger
			.getLogger(AbstractShibbolethServlet.class.getCanonicalName());
	public static final int NETHASH_UNDEFINED = -1;
	/** Default encoding. Anything other than UTF-8 doesn't make sense nowadays. */
	public static final String ENCODING = "UTF-8";
	public static final Charset ENCODING_CHARSET = Charset.forName(ENCODING);
	/**
	 * Preferred language for IdP DisplayNames. Note that changing the UI
	 * language requires changing the resources, and thus requires recompiling
	 * the wabapp anyway.
	 */
	public static final String DEFAULT_LANGUAGE = "en";

	/** root URL of servlet, as visible externally. */
	protected String webRoot;
	/**
	 * list of StorageService prefixes used in {@code target=} parameters. these
	 * are safe for use in in {@code target=} parameters (obviously), but
	 * non-bookmarkable.
	 */
	protected List<String> ssPrefixes;
	private MetadataUpdateThread meta;
	private String defaultEntityID;

	@Override
	public void init() throws ServletException {
		super.init();
		webRoot = getContextParameter("discovery.web.root").replaceFirst("/+$",
				"");
		ssPrefixes = Arrays.asList(getContextParameter(
				"shibboleth.storageservice.prefixes").split(" +"));
		defaultEntityID = getOptionalContextParameter("shibboleth.default.sp");
	}

	/**
	 * Get a parameter from {@link ServletContext}.
	 * 
	 * @param name
	 *            name of parameter
	 * @return value of parameter
	 * @throws ServletException
	 *             if the named parameter doesn't exist
	 */
	protected String getContextParameter(final String name)
			throws ServletException {
		final String value = getServletContext().getInitParameter(name);
		if (value == null)
			throw new ServletException("missing context parameter: " + name);
		return value;
	}

	/**
	 * Get an optional parameter from {@link ServletContext}.
	 * 
	 * @param name
	 *            name of parameter
	 * @return value of parameter, or <code>null</code> if it is missing or set
	 *         to the empty string
	 */
	protected String getOptionalContextParameter(final String name) {
		final String value = getServletContext().getInitParameter(name);
		if (value != null && !value.isEmpty())
			return value;
		return null;
	}

	/**
	 * Opens a database connection according to the configuration in the
	 * {@link ServletContext}.
	 * 
	 * @return a {@link ConnectionPool}
	 * @throws ServletException
	 *             if the required context parameters don't exist
	 */
	protected ConnectionPool getDatabaseConnectionPool()
			throws ServletException {
		try {
			return new ConnectionPool();
		} catch (final Exception e) {
			LOGGER.log(Level.SEVERE, "cannot connect to database", e);
			throw new ServletException("cannot connect to database");
		}
	}

	/**
	 * Determines (a 16-bit hash of) the network containing the connecting
	 * client's IP address. The hash is chosen so that "most" hosts in the same
	 * network end up in the same bin, while not creating too many collisions of
	 * hosts that are in different networks. This choice is heuristic because
	 * network sizes vary.
	 * 
	 * The network hash is a positive integer between 0 and 65535, or
	 * {@link #NETHASH_UNDEFINED} if the client's IP address cannot be parsed.
	 * 
	 * @param req
	 *            the client's request
	 * @return network hash
	 */
	protected static int getClientNetworkHash(final HttpServletRequest req) {
		// if the connection is from localhost, manually parse the
		// X-Forwarded-For header, if present. the servlet container doesn't do
		// that automatically, and without that we always see 127.0.0.1 when
		// proxying over HTTP.
		InetAddress client = InetAddresses.forString(req.getRemoteAddr());
		final String header = req.getHeader("X-Forwarded-For");
		if (header != null && !header.isEmpty()) {
			final String[] addrs = header.split(",");
			// find the "innermost" (rightmost) address that isn't a loopback
			// address, and use that as the client's address. of course this
			// fails if the proxy frontend isn't running on localhost, but
			// unencrypted HTTP should only really be used over the loopback
			// interface anyway.
			int last = addrs.length - 1;
			while (client.isLoopbackAddress() && last >= 0) {
				final String addr = addrs[last].trim();
				if (!addr.isEmpty())
					try {
						client = InetAddresses.forString(addr);
					} catch (final IllegalArgumentException e) {
						// unparseable address. the address we are looking for
						// is generated by the proxy frontend, which should not
						// generate invalid addresses. but if it ever does, just
						// continuing would allow the client to specify
						// arbitrary addresses.
						LOGGER.warning("unparseable IP in X-Forwarded-For: "
								+ header);
						break;
					}
				last--;
			}
		}
		LOGGER.fine("detected IP " + client.toString() + " for " + header);

		final byte[] addr = client.getAddress();
		// IPv4: uses the /16 prefix. should roughly match the network size of a
		// medium-sized organization.
		if (addr.length == 4)
			return ((addr[0] & 0xff) << 8) | (addr[1] & 0xff);
		// IPv6: uses a hash of the /48 prefix. /48 is the largest prefix
		// allocated in IPv6, so it should be common to the organization
		// containing the IP.
		if (addr.length == 16)
			return (((addr[0] + addr[2] + addr[4]) & 0xff) << 8)
					| ((addr[1] + addr[3] + addr[5]) & 0xff);
		// WTF? IPv9 from RFC1606??
		System.err.println("neither IPv4 nor IPv6: " + req.getRemoteAddr());
		return NETHASH_UNDEFINED;
	}

	/**
	 * Get the location where logos are cached, in the servlet container's temp
	 * directory.
	 * 
	 * @return a {@link File} pointing to the logo cache directory
	 */
	protected File getLogoCacheDir() {
		final File tempdir = (File) getServletContext().getAttribute(
				ServletContext.TEMPDIR);
		final File logoCache = new File(tempdir, "logos");
		return logoCache;
	}

	/**
	 * Sets the cache-control headers to allow caching.
	 * 
	 * @param resp
	 *            {@link HttpServletResponse} to modify
	 * @param maxAge
	 *            maximum caching time, in seconds
	 */
	protected void setCacheHeaders(final HttpServletResponse resp, int maxAge) {
		if (maxAge > ONE_YEAR)
			// HTTP 1.1 specifies: no longer than a year
			maxAge = ONE_YEAR;
		resp.setHeader("Cache-Control", "public, max-age=" + maxAge);
		final long now = System.currentTimeMillis();
		resp.setDateHeader("Expires", now + maxAge * 1000l);
		resp.setDateHeader("Last-Modified", now);
	}

	/**
	 * Sets the cache-control headers to disallow caching.
	 * 
	 * @param resp
	 *            {@link HttpServletResponse} to modify
	 */
	protected void setUncacheable(final HttpServletResponse resp) {
		resp.setHeader("Cache-Control", "no-cache");
		resp.setHeader("Pragma", "no-cache");
		resp.setDateHeader("Expires", System.currentTimeMillis());
	}

	/**
	 * Sends {@link StringBuilder} as response.
	 * 
	 * @param resp
	 *            {@link HttpServletResponse} to send to
	 * @param buffer
	 *            {@link StringBuilder} to send
	 * @param contentType
	 *            MIME type of data to send (probably {@code text/*})
	 */
	protected void sendResponse(final HttpServletResponse resp,
			final StringBuilder buffer, final String contentType)
			throws IOException {
		resp.setContentType(contentType + ";charset=" + ENCODING);
		resp.setCharacterEncoding(ENCODING);
		final byte[] data = buffer.toString().getBytes(ENCODING_CHARSET);
		resp.setContentLength(data.length);
		final ServletOutputStream out = resp.getOutputStream();
		out.write(data);
		out.close();
	}

	/**
	 * Redirects the client to the "full" discovery.
	 * 
	 * @param resp
	 *            {@link HttpServletResponse} to send the redirect to
	 * @param params
	 *            {@link LoginParams} derived from the client's request, so that
	 *            they are passed to discovery
	 */
	protected void sendRedirectToFullDiscovery(final HttpServletResponse resp,
			final LoginParams params) throws IOException {
		final StringBuilder buffer = new StringBuilder();
		buffer.append(webRoot).append("/discovery/full?");
		params.appendToURL(buffer, "&");
		resp.sendRedirect(buffer.toString());
	}

	/**
	 * Gets the 24-hour interval containing the current time instant. This
	 * doesn't necessarily have anything to do with the calendar day; it is
	 * simply the number of seconds since 1970-01-01 00:00:00, divided by 86400
	 * (24 hours). This may or may not include leap seconds.
	 * 
	 * Used to work around inconsistent date handling among databases: there is
	 * no standard way of doing arithmetic with dates that works across
	 * databases, so we just use an integer field do and some local arithmetic
	 * instead.
	 * 
	 * @return the current day number as an integer
	 */
	public static int getCurrentDay() {
		// not documented whether leap seconds are included. doesn't matter;
		// we only really need accuracy to be much smaller than the 31 day
		// interval used by database cleanup.
		return (int) (System.currentTimeMillis() / 86400000);
	}

	/**
	 * Reads a classpath resource into a {@code byte[]}.
	 * 
	 * @param resource
	 *            name of the resource to read, relative to the
	 *            {@link AbstractShibbolethServlet} class.
	 * @return the resource as a {@code byte[]}
	 * @throws ServletException
	 *             if the resource doesn't exist
	 */
	protected static byte[] getResource(final String resource)
			throws ServletException {
		final InputStream in = AbstractShibbolethServlet.class
				.getResourceAsStream(resource);
		if (in == null)
			throw new ServletException("missing resource " + resource);
		try {
			return ByteStreams.toByteArray(in);
		} catch (final IOException e) {
			throw new ServletException("cannot read resource " + resource);
		} finally {
			try {
				in.close();
			} catch (final IOException e) {
				LOGGER.log(Level.WARNING, "cannot close resource " + resource,
						e);
			}
		}
	}

	/**
	 * Reads a classpath resource into a {@link String}.
	 * 
	 * @param resource
	 *            name of the resource to read, relative to the
	 *            {@link AbstractShibbolethServlet} class.
	 * @return the resource as a {@link String}
	 * @throws ServletException
	 *             if the resource doesn't exist
	 */
	protected static String getResourceAsString(final String resource)
			throws ServletException {
		try {
			return new String(getResource(resource), ENCODING);
		} catch (final IOException e) {
			LOGGER.log(Level.SEVERE, "cannot read resource " + resource, e);
			throw new ServletException("cannot read resource " + resource);
		}
	}

	/**
	 * Checks whether a URL refers to a StorageService configured for the
	 * Shibboleth SP.
	 * 
	 * @param url
	 *            the URL to check
	 * @return <code>true</code> if this URL refers to a StorageService
	 */
	protected boolean isStorageService(final String target) {
		for (final String p : ssPrefixes)
			if (target.startsWith(p))
				return true;
		return false;
	}

	/**
	 * Validates that a URL is safe in the sense that redirecting a browser to
	 * that URL won't trigger any unexpected behavior. This works around
	 * misfeatures like the "javascript:" URL non-scheme, which definitely isn't
	 * a good protocol to redirect to, but also prevents redirects to "custom"
	 * protocol handlers that will start an external program.
	 * 
	 * Note that no check for malicious content is performed, because all modern
	 * browsers have built-in phishing and attack-site detection.
	 * 
	 * @param url
	 *            the URL to check
	 * @return <code>true</code> if it is safe to redirect a browser to it
	 */
	protected boolean isSafeURL(final String url) {
		// HTTP and HTTPS URLs are known to be safe in that they do not trigger
		// unexpected browser behavior. phishing and malware sites should be
		// protected against by the browser's phishing-and-malware protection.
		if (url.startsWith("http://") || url.startsWith("https://"))
			return true;

		// absolute URLs without the scheme component simply use whatever
		// protocol they used to reach the discovery. because it only supports
		// HTTP(S), the protocol is safe as well.
		// note: according to RFC3986, the URL scheme cannot contain slashes, so
		// if the URL does start with a slash, it must be absolute.
		if (url.startsWith("/"))
			return true;

		// any other protocol is probably unsafe to use. note that there is no
		// Shibboleth plugin for non-HTTP(S) protocols anyway.
		return false;
	}

	/**
	 * Obtains the shibboleth login URL and login target URL for an
	 * {@link HttpServletRequest}. Uses, in order of prefererence:
	 * <ol>
	 * <li>the explicit {@code login} and {@code target} URL parameters, if
	 * present
	 * <li>login and target parsed from shibboleth's {@code return} parameter,
	 * if present
	 * <li>the configured default login and target URLs
	 * </ol>
	 * 
	 * @param req
	 *            the client request
	 * @param resp
	 *            client response, for sending errors
	 * @return the {@link LoginParams} describing the combination, or
	 *         <code>null</code> if no defaults are configured
	 * @throws IOException
	 *             if an error occurs but sending it causes an
	 *             {@link IOException}
	 */
	protected LoginParams parseLoginParams(final HttpServletRequest req,
			final HttpServletResponse resp) throws IOException {
		String spEntityID = req.getParameter("entityID");
		if (spEntityID == null) {
			if (defaultEntityID == null) {
				// SP entityID is mandatory
				LOGGER.info("request without SP entityID from "
						+ req.getRemoteAddr() + "; sending error");
				resp.sendError(HttpServletResponse.SC_BAD_REQUEST,
						"missing entityID attribute");
				return null;
			}
			spEntityID = defaultEntityID;
		}

		// obtain the list of languages that the client will accept by
		// extracting just the "language" field from the locales it supports.
		final List<String> languages = new ArrayList<String>();
		for (final Enumeration<Locale> i = req.getLocales(); i
				.hasMoreElements();) {
			final Locale loc = i.nextElement();
			languages.add(loc.getLanguage());
		}

		// follow the standard and use Shibboleth's "return=" parameter when
		// present, or our own "target=" parameter if not.
		final MetadataUpdateThread meta = getMetadataUpdateThread();
		final String ret = req.getParameter("return");
		final String target = req.getParameter("target");
		final String param = req.getParameter("returnIDParam");
		final String passive = req.getParameter("isPassive");
		return new LoginParams(meta, spEntityID, ret, target, param, passive,
				languages);
	}

	protected MetadataUpdateThread getMetadataUpdateThread() {
		// get MetadataUpdateThread from DiscoveryServlet. unlocked, but the
		// value never changes anyway, and two threads writing the same value
		// should be safe.
		if (meta == null)
			meta = (MetadataUpdateThread) getServletContext().getAttribute(
					MetadataUpdateThread.class.getCanonicalName());
		return meta;
	}

	protected void sendRedirectToShibboleth(final HttpServletResponse resp,
			final LoginParams params, final String encodedIdPEntityID)
			throws IOException {
		final String login = params.getReturnLocation();
		// send 403 when the return URL isn't allowed, but 503 when we just
		// can't figure it out. the important difference is that for the 503,
		// retrying might work.
		if (login == null) {
			resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
					"cannot find redirect for " + params.getSPEntityID());
			return;
		}
		if (!params.isValidReturnLocation()) {
			resp.sendError(HttpServletResponse.SC_FORBIDDEN,
					"refusing to redirect to " + login);
			return;
		}

		// make sure that we don't land the user on something unsafe after login
		final String target = params.getTarget();
		if (target != null && !isSafeURL(target) && !isStorageService(target)) {
			LOGGER.info("refusing login to unsafe url " + target);
			resp.sendError(HttpServletResponse.SC_FORBIDDEN,
					"refusing login to " + target);
			return;
		}

		// check whether the return URL already has a query string. this is
		// needed to decide whether to append "?entityID=x" or "&entityID=x",
		// but has the side effect of rejecting syntactically invalid URLs.
		final URL url;
		try {
			url = new URL(login);
		} catch (final MalformedURLException e) {
			LOGGER.info("refusing login at invalid url " + login);
			resp.sendError(HttpServletResponse.SC_FORBIDDEN,
					"refusing login at " + login);
			return;
		}

		// append the discovered entityID, if any. the only case when the
		// entityID may be null is when isPassive was specified as true, but the
		// user doesn't have a cookie.
		final StringBuilder buffer = new StringBuilder(login);
		if (encodedIdPEntityID != null) {
			if (url.getQuery() == null)
				buffer.append('?');
			else
				buffer.append('&');
			buffer.append(params.getEncodedReturnIDParam());
			buffer.append('=');
			buffer.append(encodedIdPEntityID);
		}
		resp.sendRedirect(buffer.toString());
	}
}
