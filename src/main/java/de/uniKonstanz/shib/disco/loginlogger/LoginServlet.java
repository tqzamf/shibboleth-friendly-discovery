package de.uniKonstanz.shib.disco.loginlogger;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import de.uniKonstanz.shib.disco.AbstractShibbolethServlet;

/**
 * Logs login requests, then redirects to the actual shibboleth login URL.
 * Counts are aggregating for 10 minutes and uploaded in batch to keep database
 * load down.
 * 
 * Doesn't distinguish between successful and unsuccessful login requests,
 * because doing so would require intercepting the login request after the IdP
 * has verified the user's credentials, which the discovery host isn't supposed
 * to do. Thus a user can mess up the statistics by sending thousands of
 * requests, but he will mostly affect his own network, and only for the next 30
 * days.
 */
@SuppressWarnings("serial")
public class LoginServlet extends AbstractShibbolethServlet {
	private static final Logger LOGGER = Logger.getLogger(LoginServlet.class
			.getCanonicalName());
	/** Cookie name. Uses the common reserved namespace. */
	public static final String IDP_COOKIE = "shibboleth-discovery";
	/** Make the cookie last for a year before it spoils. */
	private static final int COOKIE_LIFETIME = 60 * 60 * 24 * 365;
	public String defaultTarget;
	private LoadingCache<LoginTuple, Counter> counter;
	private DatabaseWorkerThread updateThread;
	private String webRoot;
	private String defaultLogin;
	private DatabaseCleanupThread cleanupThread;
	private CounterFlushThread flushThread;

	@Override
	public void init() throws ServletException {
		updateThread = new DatabaseWorkerThread(getDatabaseConnection());
		// note: separate database; they cannot be shared safely
		cleanupThread = new DatabaseCleanupThread(getDatabaseConnection());

		webRoot = getWebRoot();
		defaultTarget = getContextDefaultParameter("target");
		defaultLogin = getContextDefaultParameter("login");
		if (!defaultLogin.startsWith("https://"))
			LOGGER.warning("shibboleth login URL isn't absolute or non-SSL: "
					+ defaultLogin);

		// cache abused as a way of aggregating counts for 10 minutes
		counter = CacheBuilder.newBuilder()
				.expireAfterWrite(10, TimeUnit.MINUTES)
				.maximumSize(AbstractShibbolethServlet.MAX_IDPS)
				.removalListener(new RemovalListener<LoginTuple, Counter>() {
					public void onRemoval(
							final RemovalNotification<LoginTuple, Counter> entry) {
						updateThread.enqueue(entry);
					}
				}).build(new CacheLoader<LoginTuple, Counter>() {
					@Override
					public Counter load(final LoginTuple key) {
						return new Counter();
					}
				});
		flushThread = new CounterFlushThread(counter);
		updateThread.start();
		cleanupThread.start();
		flushThread.start();
	}

	@Override
	public void destroy() {
		// push all counts in memory to the database before terminating
		counter.invalidateAll();
		updateThread.shutdown();
		cleanupThread.shutdown();
		flushThread.shutdown();
	}

	@Override
	protected void doGet(final HttpServletRequest req,
			final HttpServletResponse resp) throws IOException {
		resp.setCharacterEncoding(ENCODING);
		// get target attribute, or use default. if none given and no default,
		// that's a fatal error; we cannot recover from that.
		final LoginParams params = LoginParams.parse(req, defaultTarget,
				defaultLogin);
		if (params == null) {
			LOGGER.warning("request without required attributes from "
					+ req.getRemoteAddr() + "; sending error");
			resp.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED,
					"missing target/login or return attributes");
			return;
		}

		// get entityID for shibboleth login URL. if the entityID is missing for
		// some reason, redirect to discovery again so the user can pick one.
		final String entityID = req.getParameter("entityID");
		if (entityID == null) {
			LOGGER.info("login request without entityID from "
					+ req.getRemoteAddr() + "; redirecting to discovery");
			resp.sendRedirect(webRoot + "/discovery/full?target="
					+ params.getEncodedTarget() + "&login="
					+ params.getEncodedLogin());
			return;
		}

		// count the entityID and redirect user to shibboleth login URL
		final int ipHash = getClientNetworkHash(req);
		if (ipHash >= 0)
			counter.getUnchecked(new LoginTuple(ipHash, entityID)).increment();
		final String encodedEntityID = URLEncoder.encode(entityID, "UTF-8");
		final Cookie cookie = new Cookie(IDP_COOKIE, encodedEntityID);
		cookie.setMaxAge(COOKIE_LIFETIME);
		resp.addCookie(cookie);
		// disallow caching; we want to see every login
		setCacheHeaders(resp, 0);
		resp.sendRedirect(params.getLogin() + "?SAMLDS=1&entityID="
				+ encodedEntityID + "&target=" + params.getEncodedTarget());
	}
}
