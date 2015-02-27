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

public class LoginParams {
	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static final Logger LOGGER = Logger.getLogger(LoginParams.class
			.getCanonicalName());
	private final String login;
	private final String target;
	private final String encodedLogin;
	private final String encodedTarget;

	public LoginParams(final String login, final String target) {
		this.login = login;
		this.target = target;
		encodedLogin = encode(login);
		encodedTarget = encode(target);
	}

	private static String encode(final String value) {
		try {
			return URLEncoder.encode(value, AbstractShibbolethServlet.ENCODING);
		} catch (final UnsupportedEncodingException e) {
			throw new RuntimeException(AbstractShibbolethServlet.ENCODING
					+ " unsupported!?", e);
		}
	}

	public String getLogin() {
		return login;
	}

	public String getEncodedLogin() {
		return encodedLogin;
	}

	public String getEncodedTarget() {
		return encodedTarget;
	}

	public boolean canBookmark() {
		return target.startsWith("http://") || target.startsWith("https://");
	}

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
		if (param != null)
			return param;
		// use the value from "return=" parameter next, or the default if one is
		// given.
		if (fallback != null)
			return fallback;
		if (deflt != null)
			return deflt;
		// if there is no default, we have a missing parameter
		return null;
	}
}
