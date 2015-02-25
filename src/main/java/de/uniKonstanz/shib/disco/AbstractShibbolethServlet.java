package de.uniKonstanz.shib.disco;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;

import com.google.common.io.ByteStreams;
import com.google.common.net.InetAddresses;

import de.uniKonstanz.shib.disco.util.ReconnectingDatabase;

@SuppressWarnings("serial")
public abstract class AbstractShibbolethServlet extends HttpServlet {
	public static final int MAX_IDPS = 1000;
	protected static final String ENCODING = "UTF-8";
	private static final Logger LOGGER = Logger
			.getLogger(AbstractShibbolethServlet.class.getCanonicalName());

	protected String getContextParameter(final String name)
			throws ServletException {
		final String value = getServletContext().getInitParameter(name);
		if (value == null)
			throw new ServletException("missing context parameter: " + name);
		return value;
	}

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
	 * @return a non-overlapping 24-hour period containing the current time
	 *         instant. doesn't have any relation to the calendar day though.
	 */
	protected int getCurrentDay() {
		return (int) (System.currentTimeMillis() / 86400000);
	}

	/**
	 * Determines (a 16-bit hash of) the network containing the connecting
	 * client's IP address. The hash is chosen so that "most" hosts in the same
	 * network end up in the same bin, while not creating too many collisions of
	 * hosts that are in different networks. This choice is heuristic because
	 * network sizes vary.
	 * 
	 * @param req
	 *            the client's request
	 * @return network hash
	 */
	protected int getClientNetworkHash(final HttpServletRequest req) {
		final byte[] addr = InetAddresses.forString(req.getRemoteAddr())
				.getAddress();
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
		return -1;
	}

	protected File getLogoCacheDir() {
		final File tempdir = (File) getServletContext().getAttribute(
				ServletContext.TEMPDIR);
		final File logoCache = new File(tempdir, "logos");
		return logoCache;
	}

	protected String getWebRoot() throws ServletException {
		final String webRoot = getContextParameter("discovery.web.root");
		if (webRoot.endsWith("/"))
			return webRoot.substring(0, webRoot.length() - 1);
		return webRoot;
	}

	protected String getDefaultTarget() throws ServletException {
		final String defaultTarget = getContextParameter("shibboleth.default.target");
		if (defaultTarget.isEmpty())
			return null;
		return defaultTarget;
	}

	protected String getResourceAsString(final String resource)
			throws ServletException {
		final InputStream in = DiscoveryServlet.class
				.getResourceAsStream(resource);
		if (in == null) {
			LOGGER.severe("cannot find resource " + resource);
			throw new ServletException("resource " + resource + " not found");
		}
		try {
			final byte[] bytes = ByteStreams.toByteArray(in);
			return new String(bytes, ENCODING);
		} catch (final IOException e) {
			LOGGER.log(Level.SEVERE, "cannot read resource " + resource, e);
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
}
