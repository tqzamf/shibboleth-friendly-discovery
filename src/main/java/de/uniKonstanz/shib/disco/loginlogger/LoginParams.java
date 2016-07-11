package de.uniKonstanz.shib.disco.loginlogger;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import javax.servlet.http.HttpServletRequest;

import de.uniKonstanz.shib.disco.AbstractShibbolethServlet;

/**
 * Represents a combination of shibboleth login URL and login target URL, parsed
 * from a {@link HttpServletRequest} with
 * {@link #parse(HttpServletRequest, String, String)}.
 */
public class LoginParams {
	private final String ret;
	private final String target;
	private final String sp;
	private final String encodedReturn;
	private final String encodedTarget;
	private final String encodedSP;
	private final String encodedParam;
	private final boolean passive;

	/**
	 * @param ret
	 *            shibboleth return URL
	 * @param target
	 *            login target URL
	 * @param sp
	 *            entityID of the SP
	 * @param returnIDParam
	 *            alternate name of the entityID parameter in discovery response
	 * @param passive
	 *            <code>true</code> if <code>isPassive</code> was set in the
	 *            request
	 */
	public LoginParams(final String ret, final String target, final String sp,
			final String returnIDParam, final boolean passive) {
		this.ret = ret;
		this.target = target;
		this.sp = sp;
		this.passive = passive;
		encodedParam = encode(returnIDParam);
		encodedReturn = encode(ret);
		encodedTarget = encode(target);
		encodedSP = encode(sp);
	}

	/** URL-encodes a string for inclusion in query parameters. */
	private static String encode(final String value) {
		if (value == null)
			return null;

		try {
			return URLEncoder.encode(value, AbstractShibbolethServlet.ENCODING);
		} catch (final UnsupportedEncodingException e) {
			throw new RuntimeException(AbstractShibbolethServlet.ENCODING
					+ " unsupported!?", e);
		}
	}

	/**
	 * Gets the shibboleth return URL in plain, unencoded format. This is unsafe
	 * to pass in query parameters.
	 * 
	 * @return shibboleth login URL
	 */
	public String getReturn() {
		return ret;
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
	 * Gets the SP entityID in plain, unencoded format. This is unsafe to pass
	 * in query parameters.
	 * 
	 * @return entityID of the SP
	 */
	public String getSPEntityID() {
		return sp;
	}

	/**
	 * Gets the shibboleth return URL, URL-encoded so it is safe to pass in
	 * query parameters.
	 * 
	 * @return shibboleth login URL
	 */
	public String getEncodedReturn() {
		return encodedReturn;
	}

	/**
	 * Gets the login target URL, URL-encoded so it is safe to pass in query
	 * parameters.
	 * 
	 * @return login target URL
	 */
	public String getEncodedTarget() {
		return encodedTarget;
	}

	/**
	 * Gets the SP entityID, URL-encoded so it is safe to pass in query
	 * parameters.
	 * 
	 * @return login target URL
	 */
	public String getEncodedSPEntityID() {
		return encodedSP;
	}

	/**
	 * Gets the name of the entityID discovery response parameter. Always
	 * URL-encoded so it is safe to pass in query parameters.
	 * 
	 * @return login target URL
	 */
	public String getEncodedReturnIDParam() {
		return encodedParam;
	}

	/**
	 * Appends the {@code entityID=} (SP entityID) and {@code return=} or
	 * {@code target=} parameters to a URL. The parameters are properly escaped.
	 * 
	 * @param buffer
	 *            the {@link StringBuilder} holding the URL
	 * @param ampersand
	 *            either <code>&</code> for literal URLs or <code>&amp;</code>
	 *            for direct inclusion into HTML
	 */
	public void appendToURL(final StringBuilder buffer, final String ampersand) {
		buffer.append("entityID=").append(encodedSP);
		if (encodedParam != null)
			buffer.append(ampersand).append("returnIDParam=")
					.append(encodedParam);

		if (ret != null)
			buffer.append(ampersand).append("return=").append(encodedReturn);
		else if (target != null)
			buffer.append(ampersand).append("target=").append(encodedTarget);
	}

	/**
	 * Determines whether the target will likely remain valid beyond the
	 * lifetime of the current shibboleth session.
	 * 
	 * @return <code>true</code> if it is sensible to bookmark a link to this
	 *         target
	 */
	public boolean canBookmark() {
		// assume everything coming from the shibboleth daemon is unsafe to
		// bookmark
		if (ret != null)
			return false;
		// if the admin explicitly hardcodes a target, assume they know what
		// they're doing, and that thus the target will remain valid for a
		// while.
		// this may be asking a lot of some admins though.
		if (target != null)
			return true;
		// if there is no return or target URL, we just use the default. the
		// actual URL may change, but it should always work, so it should be OK
		// to bookmark it.
		return true;
	}

	public boolean isPassive() {
		return passive;
	}
}
