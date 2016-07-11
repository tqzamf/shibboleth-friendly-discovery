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
import de.uniKonstanz.shib.disco.metadata.IdPMeta;
import de.uniKonstanz.shib.disco.metadata.MetadataUpdateThread;
import de.uniKonstanz.shib.disco.metadata.XPMeta;
import de.uniKonstanz.shib.disco.util.ConnectionPool;

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
	/** Maximum size for login counter cache. */
	private static final int MAX_LOGIN_CACHE = 262144;
	private LoadingCache<LoginTuple, LoginTuple> counter;
	private DatabaseWorkerThread updateThread;
	private DatabaseCleanupThread cleanupThread;
	private CounterFlushThread flushThread;
	private MetadataUpdateThread meta;

	@Override
	public void init() throws ServletException {
		super.init();
		final ConnectionPool db = getDatabaseConnectionPool();
		updateThread = new DatabaseWorkerThread(db);
		cleanupThread = new DatabaseCleanupThread(db);

		if (!defaultLogin.startsWith("https://"))
			LOGGER.warning("shibboleth login URL is relative or non-SSL: "
					+ defaultLogin);

		// cache abused as a way of aggregating counts for 10 minutes.
		// cache size is limited so that the memory consumption cannot grow
		// without limit: each object is ~32 bytes (2 ints, a reference,
		// overhead) plus overhead for the map, so 262144 entries should be on
		// the order of 8-32 MB, which is still less than an idle Tomcat serving
		// zero webapps.
		counter = CacheBuilder.newBuilder()
				.expireAfterWrite(10, TimeUnit.MINUTES)
				.maximumSize(MAX_LOGIN_CACHE)
				.removalListener(new RemovalListener<LoginTuple, LoginTuple>() {
					@Override
					public void onRemoval(
							final RemovalNotification<LoginTuple, LoginTuple> entry) {
						updateThread.enqueue(entry.getKey());
					}
				}).build(new CacheLoader<LoginTuple, LoginTuple>() {
					@Override
					public LoginTuple load(final LoginTuple key) {
						return key;
					}
				});
		flushThread = new CounterFlushThread(counter);
		updateThread.start();
		cleanupThread.start();
		flushThread.start();
	}

	@Override
	public void destroy() {
		super.destroy();
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
		final LoginParams params = parseLoginParams(req);
		if (params == null) {
			LOGGER.info("request without valid attributes from "
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
			sendRedirectToFullDiscovery(resp, params);
			return;
		}

		// only count known-valid entityIDs so that an attacker cannot flood the
		// database with thousands of invalid entityIDs. perform the redirect
		// anyway, to avoid becoming an unnecessary point of failure.
		final int ipHash = getClientNetworkHash(req);
		if (ipHash >= 0) {
			final XPMeta idp = getEntityID(entityID);
			if (idp != null) {
				final LoginTuple key = new LoginTuple(ipHash, idp);
				counter.getUnchecked(key).incrementCounter();
			} else
				LOGGER.info("login request with unknown entityID=" + entityID
						+ " from " + req.getRemoteAddr());
		}
		// set "cookie favorite"
		final String encodedEntityID = URLEncoder.encode(entityID, ENCODING);
		final Cookie cookie = new Cookie(IDP_COOKIE, encodedEntityID);
		cookie.setMaxAge(COOKIE_LIFETIME);
		resp.addCookie(cookie);
		// redirect user to shibboleth login URL. disallow caching; we want to
		// see every login.
		setCacheHeaders(resp, 0);

		// this allows redirects to arbitrary sites, which can be used for some
		// obscure attacks which rely on the user recognizing a URL as safe, not
		// noticing that it redirects to another site, and entering credentials.
		// for a modern browsers, this may also involve clicking through a
		// (generally reddish) phishing warning.
		// the only countermeasure for now is to limit redirects to known-safe
		// URLs, so we don't redirect to "javascript:your-xss-here", or end up
		// opening external programs.
		if (!isSafeURL(params.getLogin())) {
			LOGGER.info("refusing redirect to unsafe url " + params.getLogin()
					+ "; sending error");
			resp.sendError(HttpServletResponse.SC_FORBIDDEN,
					"refusing to redirect to " + params.getLogin());
			return;
		}
		if (!isSafeURL(params.getTarget())
				&& !isStorageService(params.getTarget())) {
			LOGGER.info("refusing login to unsafe url " + params.getTarget()
					+ "; sending error");
			resp.sendError(HttpServletResponse.SC_FORBIDDEN,
					"refusing to redirect to " + params.getTarget());
			return;
		}
		resp.sendRedirect(params.getLogin() + "?SAMLDS=1&entityID="
				+ encodedEntityID + "&target=" + params.getEncodedTarget());
	}

	/**
	 * Checks if an entityID is valid for logging, ie. present in Shibboleth's
	 * metadata. If present, returns the {@link IdPMeta} object, else
	 * <code>null</code>.
	 * 
	 * @param entityID
	 *            entityID to check
	 * @return the metadata object, or <code>null</code> if not present in the
	 *         metadata
	 */
	private XPMeta getEntityID(final String entityID) {
		// get MetadataUpdateThread from DiscoveryServlet. unlocked, but the
		// value never changes anyway, and two threads writing the same value
		// should be safe.
		if (meta == null)
			meta = (MetadataUpdateThread) getServletContext().getAttribute(
					MetadataUpdateThread.class.getCanonicalName());
		if (meta == null) {
			LOGGER.info("no metadata yet; not logging " + entityID);
			return null;
		}
		return meta.getMetadata(entityID);
	}
}
