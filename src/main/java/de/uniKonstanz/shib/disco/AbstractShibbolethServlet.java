package de.uniKonstanz.shib.disco;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.io.ByteStreams;
import com.google.common.net.InetAddresses;

import de.uniKonstanz.shib.disco.util.ReconnectingDatabase;

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
	/**
	 * Number of supported IdPs. It will handle more, but performance will
	 * suffer. If set to a value that is too large, memory consumption can
	 * become excessive.
	 * 
	 * 1000 was chosen because at that point, usability will already suffer
	 * severely because of the long page for full discovery, so it makes sense
	 * to choose a different discovery provider instead of raising the limit.
	 */
	public static final int MAX_IDPS = 1000;
	/**
	 * Preferred language for IdP DisplayNames. Note that changing the UI
	 * language requires changing the resources, and thus requires recompiling
	 * the wabapp anyway.
	 */
	public static final String LANGUAGE = "en";

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
	protected String getContextDefaultParameter(final String suffix)
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
	 * @return a {@link ReconnectingDatabase}
	 * @throws ServletException
	 *             if the required context parameters don't exist
	 */
	protected ReconnectingDatabase getDatabaseConnection()
			throws ServletException {
		final String jdbcDriver = getContextParameter("database.jdbc.driver");
		try {
			Class.forName(jdbcDriver);
		} catch (final ClassNotFoundException e) {
			LOGGER.log(Level.SEVERE, "cannot load JDBC driver " + jdbcDriver, e);
			throw new ServletException("cannot connect to database");
		}

		final String dbURL = getContextParameter("database.jdbc.url");
		try {
			return new ReconnectingDatabase(dbURL);
		} catch (final SQLException e) {
			LOGGER.log(Level.SEVERE, "cannot connect to database " + dbURL, e);
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
	 * Get the {@code "discovery.web.root"} context parameter, without trailing
	 * slashes.
	 * 
	 * @return the {@code "discovery.web.root"} context parameter
	 * @throws ServletException
	 *             if the {@code "discovery.web.root"} context parameter doesn't
	 *             exist
	 */
	protected String getWebRoot() throws ServletException {
		return getContextParameter("discovery.web.root")
				.replaceFirst("/+$", "");
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
		resp.setContentType(contentType);
		final PrintWriter out = resp.getWriter();
		out.write(buffer.toString());
		out.close();
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
}
