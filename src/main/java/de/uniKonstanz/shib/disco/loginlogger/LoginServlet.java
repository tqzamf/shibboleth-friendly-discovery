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
import de.uniKonstanz.shib.disco.util.ReconnectingDatabase;

@SuppressWarnings("serial")
public class LoginServlet extends AbstractShibbolethServlet {
	public static final String IDP_COOKIE = "shibboleth-discovery";
	private static final int COOKIE_LIFETIME = 60 * 60 * 24 * 365;
	private static final Logger LOGGER = Logger.getLogger(LoginServlet.class
			.getCanonicalName());
	private ReconnectingDatabase db;
	public String defaultTarget;
	private LoadingCache<LoginTuple, Counter> counter;
	DatabaseWorkerThread updateThread;
	private String webRoot;
	private String defaultLogin;

	@Override
	public void init() throws ServletException {
		db = getDatabaseConnection();
		updateThread = new DatabaseWorkerThread(db);

		webRoot = getWebRoot();
		defaultTarget = getContextDefaultParameter("target");
		defaultLogin = getContextDefaultParameter("login");
		if (!defaultLogin.startsWith("https://"))
			LOGGER.warning("shibboleth login URL isn't absolute or non-SSL: "
					+ defaultLogin);

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
		updateThread.start();
	}

	@Override
	public void destroy() {
		counter.invalidateAll();
		updateThread.shutdown();
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
		resp.sendRedirect(params.getLogin() + "?SAMLDS=1&entityID="
				+ encodedEntityID + "&target=" + params.getEncodedTarget());
	}
}
