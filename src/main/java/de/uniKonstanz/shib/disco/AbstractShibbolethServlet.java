package de.uniKonstanz.shib.disco;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import com.google.common.io.ByteStreams;
import com.google.common.net.InetAddresses;

import de.uniKonstanz.shib.disco.loginlogger.LoginParams;
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
	public static final String LANGUAGE = "en";

	/** root URL of servlet, as visible externally. */
	protected String webRoot;
	/** default URL to visit after login if none given. */
	protected String defaultTarget;
	/** default URL to /Shibboleth.sso/Login, used if none given. */
	protected String defaultLogin;
	/**
	 * list of StorageService prefixes used in {@code target=} parameters. these
	 * are safe for use in in {@code target=} parameters (obviously), but
	 * non-bookmarkable.
	 */
	protected List<String> ssPrefixes;

	@Override
	public void init() throws ServletException {
		super.init();
		webRoot = getContextParameter("discovery.web.root").replaceFirst("/+$",
				"");
		defaultTarget = getContextDefaultParameter("target");
		defaultLogin = getContextDefaultParameter("login");
		ssPrefixes = Arrays.asList(getContextParameter(
				"shibboleth.storageservice.prefixes").split(" +"));
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
	 * Get a parameter from {@link ServletContext}, mapping the empty string to
	 * <code>null</code>.
	 * 
	 * @param suffix
	 *            name suffix of parameter, appended to
	 *            {@code "shibboleth.default."}
	 * @return value of parameter, or <code>null</code> if the value is
	 *         <code>""</code>
	 * @throws ServletException
	 *             if the named parameter doesn't exist
	 */
	private String getContextDefaultParameter(final String suffix)
			throws ServletException {
		final String dflt = getContextParameter("shibboleth.default." + suffix);
		if (dflt.isEmpty())
			return null;
		return dflt;
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
		// that automatically, and without that we always see 127.0.0.1.
		InetAddress client = InetAddresses.forString(req.getRemoteAddr());
		final String header = req.getHeader("X-Forwarded-For");
		if (header != null && !header.isEmpty() && client.isLoopbackAddress()) {
			final String[] entries = header.split(",");
			if (entries.length >= 1 && !entries[0].trim().isEmpty())
				client = InetAddresses.forString(entries[0].trim());
		}
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
	 *            maximum caching time, in seconds. 0 disallows caching.
	 */
	protected void setCacheHeaders(final HttpServletResponse resp, int maxAge) {
		if (maxAge == 0) {
			resp.setHeader("Cache-Control", "no-cache");
			resp.setHeader("Pragma", "no-cache");
			resp.setDateHeader("Expires", System.currentTimeMillis());
			return;
		}

		if (maxAge > ONE_YEAR)
			// HTTP 1.1 specifies: no longer than a year
			maxAge = ONE_YEAR;
		resp.setHeader("Cache-Control", "public, max-age=" + maxAge);
		resp.setDateHeader("Expires", System.currentTimeMillis() + maxAge
				* 1000);
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
		params.appendToURL(buffer);
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
	 * @return the {@link LoginParams} describing the combination, or
	 *         <code>null</code> if no defaults are configured
	 */
	protected LoginParams parseLoginParams(final HttpServletRequest req) {
		// Shibboleth adds its "return=" parameter to every discovery request.
		// that's generally a good thing, but prevents the links from being
		// bookmarkable. we support it as a backup, but prefer the explicit
		// login+target from the URL if present.
		final String ret = req.getParameter("return");
		String retLogin = null;
		String retTarget = null;
		if (ret != null) {
			try {
				final URL url = new URL(ret);
				retLogin = new URL(url.getProtocol(), url.getHost(),
						url.getPort(), url.getPath()).toExternalForm();
				for (final NameValuePair param : URLEncodedUtils.parse(
						url.getQuery(), ENCODING_CHARSET)) {
					if (param.getName() == null || param.getValue() == null)
						continue;
					if (param.getName().equalsIgnoreCase("target"))
						retTarget = param.getValue();
				}
			} catch (final MalformedURLException e) {
				LOGGER.log(Level.INFO, "invalid return URL: " + ret, e);
			}
		}

		final String target = getParameter(req, "target", retTarget,
				defaultTarget);
		final String login = getParameter(req, "login", retLogin, defaultLogin);
		if (target == null || login == null)
			return null;
		return new LoginParams(login, target, !isStorageService(target));
	}

	/**
	 * Helper method to get an explicit URL parameter, or one form the
	 * {@code return} parameters, or the default, or <code>null</code>.
	 */
	private static String getParameter(final HttpServletRequest req,
			final String name, final String fallback, final String deflt)
			throws NoSuchElementException {
		// if an explicit parameter is configured in the URL then use it,
		// overriding the one given by Shibboleth's "return=" parameter if
		// present.
		// this order is chosen because it is easy to not pass a parameter in
		// the discovery URL, but Shibboleth cannot be told to omit the
		// "return=" parameter.
		final String param = req.getParameter(name);
		if (param != null && !param.isEmpty())
			return param;
		// use the value from "return=" parameter next, or the default if one is
		// given.
		if (fallback != null && !fallback.isEmpty())
			return fallback;
		if (deflt != null & !deflt.isEmpty())
			return deflt;
		// if there is no default, we have a missing parameter
		return null;
	}
}
