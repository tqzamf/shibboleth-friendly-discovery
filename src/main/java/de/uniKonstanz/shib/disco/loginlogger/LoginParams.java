package de.uniKonstanz.shib.disco.loginlogger;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import de.uniKonstanz.shib.disco.AbstractShibbolethServlet;

/**
 * Represents a combination of shibboleth login URL and login target URL, parsed
 * from a {@link HttpServletRequest} with
 * {@link #parse(HttpServletRequest, String, String)}.
 */
public class LoginParams {
	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static final Logger LOGGER = Logger.getLogger(LoginParams.class
			.getCanonicalName());
	private final String login;
	private final String target;
	private final String encodedLogin;
	private final String encodedTarget;

	/**
	 * @param login
	 *            shibboleth login URL
	 * @param target
	 *            login target URL
	 */
	private LoginParams(final String login, final String target) {
		this.login = login;
		this.target = target;
		encodedLogin = encode(login);
		encodedTarget = encode(target);
	}

	/** URL-encodes a string for inclusion in query parameters. */
	private static String encode(final String value) {
		try {
			return URLEncoder.encode(value, AbstractShibbolethServlet.ENCODING);
		} catch (final UnsupportedEncodingException e) {
			throw new RuntimeException(AbstractShibbolethServlet.ENCODING
					+ " unsupported!?", e);
		}
	}

	/**
	 * Gets the login URL in plain, unencoded format. This is unsafe to pass in
	 * query parameters.
	 * 
	 * @return shibboleth login URL
	 */
	public String getLogin() {
		return login;
	}

	/**
	 * Gets the login URL, URL-encoded so it is safe to pass in query
	 * parameters.
	 * 
	 * @return shibboleth login URL
	 */
	public String getEncodedLogin() {
		return encodedLogin;
	}

	/**
	 * Gets the target URL, URL-encoded so it is safe to pass in query
	 * parameters.
	 * 
	 * @return login target URL
	 */
	public String getEncodedTarget() {
		return encodedTarget;
	}

	/**
	 * Appends the {@code target=} and {@code login=} parameters to a URL. The
	 * parameters are properly escaped.
	 * 
	 * @param buffer
	 *            the {@link StringBuilder} holding the URL
	 */
	public void appendToURL(final StringBuilder buffer) {
		buffer.append("target=").append(encodedTarget);
		buffer.append("&amp;login=").append(encodedLogin);
	}

	/**
	 * Determines whether the target will remain valid beyond the lifetime of
	 * the current shibboleth session. If it is an entityID, then it is likely
	 * to remain valid. Conversely, the {@code ss:mem:*} scheme is known to stop
	 * working after the current shibboleth session has ended, so links
	 * containing such targets should not be bookmarked.
	 * 
	 * @return <code>true</code> if it is sensible to bookmark a link to this
	 *         target
	 */
	public boolean canBookmark() {
		// note: for simplicity, assumes that all entityIDs are HTTP or HTTPS
		// URLs
		return target.startsWith("http://") || target.startsWith("https://");
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
	 * @param defaultTarget
	 *            configured default target URL, or <code>null</code> if none
	 *            configured
	 * @param defaultLogin
	 *            configured default login URL, or <code>null</code> if none
	 *            configured
	 * @return the {@link LoginParams} describing the combination, or
	 *         <code>null</code> if no defaults are configured
	 */
	public static LoginParams parse(final HttpServletRequest req,
			final String defaultTarget, final String defaultLogin) {
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
						url.getQuery(), UTF8)) {
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
		return new LoginParams(login, target);
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
