package de.uniKonstanz.shib.disco.loginlogger;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;

import de.uniKonstanz.shib.disco.AbstractShibbolethServlet;

/**
 * Represents a combination of shibboleth login URL and login target URL, parsed
 * from a {@link HttpServletRequest} with
 * {@link #parse(HttpServletRequest, String, String)}.
 */
public class LoginParams {
	private static final Logger LOGGER = Logger.getLogger(LoginParams.class
			.getCanonicalName());
	private final String login;
	private final String target;
	private final String encodedLogin;
	private final String encodedTarget;
	private final boolean bookmarkable;

	/**
	 * @param login
	 *            shibboleth login URL
	 * @param target
	 *            login target URL
	 * @param bookmarkable
	 *            <code>true</code> if the login target URL is valid
	 *            indefinitely (ie. doesn't refer to the SP's StorageService)
	 */
	public LoginParams(final String login, final String target,
			final boolean bookmarkable) {
		this.login = login;
		this.target = target;
		this.bookmarkable = bookmarkable;
		if (bookmarkable && !target.startsWith("http://")
				&& !target.startsWith("https://"))
			LOGGER.info("non-HTTP(S) url claims to be bookmarkable: " + target);
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
	 * Gets the target URL in plain, unencoded format. This is unsafe to pass in
	 * query parameters.
	 * 
	 * @return login target URL
	 */
	public String getTarget() {
		return target;
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
		return bookmarkable;
	}
}
