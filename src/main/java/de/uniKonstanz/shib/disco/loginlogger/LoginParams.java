package de.uniKonstanz.shib.disco.loginlogger;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import de.uniKonstanz.shib.disco.AbstractShibbolethServlet;
import de.uniKonstanz.shib.disco.metadata.MetadataUpdateThread;

/**
 * Represents a combination of shibboleth login URL and login target URL, parsed
 * from a {@link HttpServletRequest} with
 * {@link #parse(HttpServletRequest, String, String)}.
 */
public class LoginParams {
	private static final Logger LOGGER = Logger.getLogger(LoginParams.class
			.getCanonicalName());

	private final String returnLocation;
	private final String target;
	private final String spEntityID;
	private final String encodedReturn;
	private final String encodedTarget;
	private final String encodedSP;
	private final String encodedParam;
	private final boolean passive;
	private final boolean validReturn;

	private boolean defaultReturn;

	/**
	 * @param meta
	 *            the {@link MetadataUpdateThread} providing information about
	 *            the SP, or <code>null</code>
	 * @param spEntityID
	 *            entityID of the SP
	 * @param returnLocation
	 *            shibboleth return URL as passed in the query string, or
	 *            <code>null</code>
	 * @param target
	 *            login target URL as passed in query string, or
	 *            <code>null</code>
	 * @param returnIDParam
	 *            alternate name of the entityID parameter in discovery
	 *            response, usually <code>null</code>
	 * @param passive
	 *            the <code>isPassive</code> parameter from the query string,
	 *            usually <code>null</code>
	 */
	public LoginParams(final MetadataUpdateThread meta,
			final String spEntityID, final String returnLocation,
			final String target, final String returnIDParam,
			final String passive) {
		this.spEntityID = spEntityID;
		this.passive = passive != null && passive.equalsIgnoreCase("true");

		if (returnLocation != null) {
			this.returnLocation = returnLocation;
			// properly validate the return URL. validation is disabled until
			// metadata is available to avoid unnecessary failures. thus it
			// forms an open redirect in this special situation, but the lack of
			// metadata has to be fixed quickly anyway because most of the
			// discovery is broken without metadata.
			validReturn = meta == null
					|| meta.isValidResponseLocation(spEntityID, returnLocation);
			// try to extract login target URL from Shibboleth's "return="
			// parameter so that filtering can be based on it.
			this.target = parseTargetURL(returnLocation);
			defaultReturn = false;
		} else if (meta != null) {
			final String defaultReturn = meta
					.getDefaultResponseLocation(spEntityID);
			// append target parameter if present
			if (target != null && defaultReturn != null)
				this.returnLocation = defaultReturn + "?target=" + target;
			else
				this.returnLocation = defaultReturn;
			this.target = target;
			// parameter came from the SP's metadata so it is known to be safe
			validReturn = true;
			this.defaultReturn = true;
		} else {
			// no explicit return URL given and no metadata. we're stuck.
			this.returnLocation = null;
			this.target = null;
			validReturn = true;
			defaultReturn = true;
		}

		// pre-encode values for appendToURL, which is called several times. the
		// LoginServlet uses encodedParam and encodedReturn as well,
		encodedSP = encode(spEntityID);
		encodedParam = encode(returnIDParam);
		encodedReturn = encode(returnLocation);
		encodedTarget = encode(this.target);
	}

	/**
	 * Extracts the login target URL from the query string of a Shibboleth
	 * return URL.
	 */
	private static String parseTargetURL(final String ret) {
		try {
			final URL url = new URL(ret);
			for (final NameValuePair param : URLEncodedUtils.parse(
					url.getQuery(), AbstractShibbolethServlet.ENCODING_CHARSET)) {
				if (param.getName() == null || param.getValue() == null)
					continue;
				if (param.getName().equalsIgnoreCase("target"))
					return param.getValue();
			}
		} catch (final MalformedURLException e) {
			LOGGER.log(Level.INFO, "invalid return URL: " + ret, e);
		}
		return null;
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
	 * Gets the Shibboleth return URL in plain, unencoded format suitable for
	 * redirects. Falls back to the default return URL if none was given on the
	 * query string, but may still return <code>null</code> if not metadata was
	 * available.
	 * 
	 * @return Shibboleth login URL
	 */
	public String getReturnLocation() {
		return returnLocation;
	}

	/**
	 * Gets the target URL in plain, unencoded format, suitable for filtering.
	 * This is unsafe to pass in query parameters.
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
		return spEntityID;
	}

	/**
	 * Gets the name of the entityID discovery response parameter. Always
	 * URL-encoded so it is safe to pass in query parameters.
	 * 
	 * @return name of the entityID parameter; usually "entityID".
	 */
	public String getEncodedReturnIDParam() {
		if (encodedParam != null)
			return encodedParam;
		return "entityID";
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

		if (returnLocation != null && !defaultReturn)
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
		if (returnLocation != null && !defaultReturn)
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

	/** @return <code>true</code> if {@code isPassive} was set to true */
	public boolean isPassive() {
		return passive;
	}

	/**
	 * @return <code>true</code> if the return location is valid according to
	 *         the SP's metadata
	 */
	public boolean isValidReturnLocation() {
		return validReturn;
	}
}
