package de.uniKonstanz.shib.disco;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import de.uniKonstanz.shib.disco.loginlogger.IdPRanking;
import de.uniKonstanz.shib.disco.loginlogger.LoginParams;
import de.uniKonstanz.shib.disco.loginlogger.LoginServlet;
import de.uniKonstanz.shib.disco.metadata.IdPMeta;
import de.uniKonstanz.shib.disco.metadata.MetadataUpdateThread;
import de.uniKonstanz.shib.disco.util.ReconnectingDatabase;

/**
 * Handles the discovery itself, providing 3 flavors of discovery:
 * <dl>
 * <dt>{@code full}
 * <dd>a list of all IdPs, along with a javascript-based search/filter box
 * <dt>{@code friendly}
 * <dd>the "most likely" IdPs for the user, and a link to the full discovery
 * <dt>{@code embed}
 * <dd>javascript code to embed the {@code friendly} discovery in a webpage; see
 * {@link #buildEmbeddedDiscovery(HttpServletRequest, HttpServletResponse, LoginParams)}
 * for details
 * </dl>
 * The "most likely" IdPs are, in order:
 * <ol>
 * <li>the last one he used, if any, as marked by a cookie named
 * {@link LoginServlet#IDP_COOKIE}
 * <li>the most popular ones for "his" network, as defined by
 * {@link #getClientNetworkHash(HttpServletRequest)}
 * <li>the globally most popular ones
 * </ol>
 * Up to {@code "discovery.friendly.idps"} (default 6) IdPs are shown in the
 * {@code friendly} and {@code embed} styles.
 */
@SuppressWarnings("serial")
public class DiscoveryServlet extends AbstractShibbolethServlet {
	private static final Logger LOGGER = Logger
			.getLogger(DiscoveryServlet.class.getCanonicalName());
	private String webRoot;
	private ReconnectingDatabase db;
	private MetadataUpdateThread meta;
	private String discoFeed;
	private String defaultTarget;
	private String friendlyHeader;
	private String fullHeader;
	private String footer;
	private IdPRanking ranking;
	private int numTopIdPs;
	private String jsHeader;
	private String otherIdPsText;
	private String defaultLogin;
	private String bookmarkNotice;
	private String wayf;

	@Override
	public void init() throws ServletException {
		webRoot = getWebRoot();
		discoFeed = getContextParameter("shibboleth.discofeed.url");
		numTopIdPs = Integer
				.parseInt(getContextParameter("discovery.friendly.idps"));
		otherIdPsText = getContextParameter("discovery.friendly.others");
		defaultTarget = getContextDefaultParameter("target");
		defaultLogin = getContextDefaultParameter("login");
		jsHeader = getResourceAsString("header.js");
		friendlyHeader = getResourceAsString("friendly-header.html");
		fullHeader = getResourceAsString("full-header.html");
		footer = getResourceAsString("footer.html");
		wayf = normalize(getResourceAsString("wayf.html"));
		bookmarkNotice = normalize(getResourceAsString("bookmark-notice.html"));

		db = getDatabaseConnection();
		ranking = new IdPRanking(db, numTopIdPs);
		meta = new MetadataUpdateThread(discoFeed, getLogoCacheDir());
		meta.start();
	}

	@Override
	public void destroy() {
		meta.interrupt();
		db.close();
	}

	/** Normalize whitespace. Not safe to use on untrusted data. */
	private String normalize(final String data) {
		return data.replaceAll("\\s+", " ");
	}

	@Override
	protected void doGet(final HttpServletRequest req,
			final HttpServletResponse resp) throws ServletException,
			IOException {
		resp.setCharacterEncoding(ENCODING);
		// get target and login attributes, or use defaults. if none given and
		// no default, that's a fatal error; we cannot recover from that.
		final LoginParams params = LoginParams.parse(req, defaultTarget,
				defaultLogin);
		if (params == null) {
			LOGGER.warning("request without required attributes from "
					+ req.getRemoteAddr() + "; sending error");
			resp.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED,
					"missing target/login or return attributes");
			return;
		}
		// redirect to full discovery if no discovery flavor specified
		final String pi = req.getPathInfo();
		if (pi == null || pi.equals("/")) {
			resp.sendRedirect(webRoot + "/discovery/full?target="
					+ params.getEncodedTarget() + "&login="
					+ params.getEncodedLogin());
			return;
		}

		// pick discovery flavor
		if (pi.equalsIgnoreCase("/full"))
			buildFullDiscovery(req, resp, params);
		else if (pi.equalsIgnoreCase("/embed"))
			buildEmbeddedDiscovery(req, resp, params);
		else if (pi.equalsIgnoreCase("/friendly"))
			buildFriendlyDiscovery(req, resp, params);
		else
			resp.sendError(HttpServletResponse.SC_NOT_FOUND,
					"Discovery service " + pi.substring(1) + " does not exist.");
	}

	/**
	 * Builds the {@code full} discovery. This is simply a list of all IdPs,
	 * formatted as buttons with logos, and a search box to locate the right IdP
	 * if there are many.
	 */
	private void buildFullDiscovery(final HttpServletRequest req,
			final HttpServletResponse resp, final LoginParams params)
			throws IOException {
		final List<IdPMeta> idps = meta.getAllMetadata();
		if (idps.isEmpty()) {
			// if there are no valid IdPs, the user cannot log in. there is no
			// point in showing an empty discovery page; just report an error
			// instead.
			LOGGER.severe("no IdPs available for discovery!");
			resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					"no institutions available for login");
			return;
		}

		final StringBuilder buffer = new StringBuilder();
		buffer.append(fullHeader);
		buildNotices(buffer, params);
		for (final IdPMeta idp : idps)
			buildHTML(buffer, idp, params);
		buffer.append(footer);
		// page won't change until the next metadata update
		setCacheHeaders(resp, MetadataUpdateThread.INTERVAL);
		sendResponse(resp, buffer, "text/html");
	}

	/**
	 * Builds the {@code friendly} discovery. This is the (default 6)
	 * "most likely" IdPs for this user, and a link to the {@code full}
	 * discovery.
	 */
	private void buildFriendlyDiscovery(final HttpServletRequest req,
			final HttpServletResponse resp, final LoginParams params)
			throws IOException {
		final List<IdPMeta> idps = getIdPList(req);
		if (idps.isEmpty()) {
			// in the (unlikely) case that none of the entityIDs are known,
			// fall back to providing the complete list. this is more useful
			// than providing just the "others" button.
			buildFullDiscovery(req, resp, params);
			return;
		}

		final StringBuilder buffer = new StringBuilder();
		buffer.append(friendlyHeader);
		buildNotices(buffer, params);
		for (final IdPMeta idp : idps)
			buildHTML(buffer, idp, params);
		buildOtherIdPsButton(buffer, params);
		buffer.append(footer);

		// page is per-user and will change if the "cookie favorite" changes, so
		// it shouldn't be cached.
		setCacheHeaders(resp, 0);
		sendResponse(resp, buffer, "text/html");
	}

	/**
	 * Builds the {@code embed} discovery. This is the same style as the
	 * {@code friendly} discovery, but packed as a JavaScript snippet that
	 * inserts the relevant HTML.
	 * 
	 * The generated JavaScript doesn't interact very much with the host page,
	 * and doesn't require anything special except that the host page doesn't
	 * use the namespaces {@code shibboleth-discovery*} (CSS, HTML IDs, Cookies)
	 * and {@code shibbolethDiscovery*} (JavaScript). It will replace the HTML
	 * element with ID {@code shibboleth-discovery}, which can be the
	 * {@code <script>} tag that loads it, or a non-javascript fallback link to
	 * discovery.
	 * 
	 * The only interaction is that if jQuery is present on the host webpage, it
	 * will be used for a few nontrivial operations. However, there are
	 * fallbacks using plain javascript, so that jQuery isn't required.
	 */
	private void buildEmbeddedDiscovery(final HttpServletRequest req,
			final HttpServletResponse resp, final LoginParams params)
			throws IOException {
		final List<IdPMeta> idps = getIdPList(req);

		final StringBuilder buffer = new StringBuilder();
		buffer.append(jsHeader);
		buffer.append("shibbolethDiscovery('").append(webRoot).append("','");
		buildNotices(buffer, params);
		for (final IdPMeta idp : idps)
			buildHTML(buffer, idp, params);
		buildOtherIdPsButton(buffer, params);
		buffer.append("<br />');");

		// page is per-user and will change if the "cookie favorite" changes, so
		// it shouldn't be cached.
		setCacheHeaders(resp, 0);
		sendResponse(resp, buffer, "text/javascript");
	}

	/**
	 * Gets the {@link #numTopIdPs} "most likely" IdPs. These are, in order:
	 * <ol>
	 * <li>the last one the client used, if any, as marked by a cookie named
	 * {@link LoginServlet#IDP_COOKIE}
	 * <li>the most popular ones for "his" network, as defined by
	 * {@link #getClientNetworkHash(HttpServletRequest)}
	 * <li>the globally most popular ones
	 * </ol>
	 */
	private List<IdPMeta> getIdPList(final HttpServletRequest req) {
		final LinkedHashSet<IdPMeta> list = new LinkedHashSet<IdPMeta>(
				numTopIdPs);
		addCookieFavorite(list, req);
		addNethashFavorites(list, req);
		if (list.size() < numTopIdPs)
			// don't unnecessarily add global favorites if there are already
			// enough nethash-local favorites.
			addGlobalFavorites(list);

		// limit to correct number of IdPs, and convert to an actual List
		final List<IdPMeta> res = new ArrayList<IdPMeta>(numTopIdPs);
		for (final IdPMeta idp : list) {
			res.add(idp);
			if (res.size() >= numTopIdPs)
				break;
		}
		return res;
	}

	/**
	 * Checks for the {@link LoginServlet#IDP_COOKIE} cookie and, if present,
	 * inserts the indicated IdP into the list.
	 */
	private void addCookieFavorite(final Collection<IdPMeta> list,
			final HttpServletRequest req) {
		final Cookie[] cookies = req.getCookies();
		if (cookies == null)
			return;

		for (final Cookie c : cookies) {
			if (c.getName() == null || c.getValue() == null)
				continue;
			if (!c.getName().equalsIgnoreCase(LoginServlet.IDP_COOKIE))
				continue;

			try {
				final String entityID = URLDecoder
						.decode(c.getValue(), "UTF-8");
				final IdPMeta idp = meta.getMetadata(entityID);
				if (idp != null) {
					list.add(idp);
					return;
				}
			} catch (final Throwable t) {
				LOGGER.info("malformed cookie: " + c.getValue());
				// bad cookie; ignored
			}
		}
	}

	/**
	 * Obtains the {@link #numTopIdPs} most popular IdPs for the client's
	 * nethash, and inserts them into the given list.
	 */
	private void addNethashFavorites(final Collection<IdPMeta> list,
			final HttpServletRequest req) {
		final List<String> entities = ranking
				.getIdPList(getClientNetworkHash(req));
		if (entities != null)
			meta.addMetadata(list, entities);
	}

	/**
	 * Obtains the {@link #numTopIdPs} most popular IdPs across all nethashes,
	 * and inserts them into the given list.
	 */
	private void addGlobalFavorites(final LinkedHashSet<IdPMeta> list) {
		final List<String> entities = ranking.getGlobalIdPList();
		if (entities != null)
			meta.addMetadata(list, entities);
	}

	/**
	 * Adds the "Where are you from" prompt, and the
	 * "you can bookmark these links" notice if appropriate.
	 */
	private void buildNotices(final StringBuilder buffer,
			final LoginParams params) {
		buffer.append(wayf);
		if (params.canBookmark())
			buffer.append(bookmarkNotice);
	}

	/** Adds the HTML for a single IdP button. */
	private void buildHTML(final StringBuilder buffer, final IdPMeta idp,
			final LoginParams params) {
		// link
		buffer.append("<a href=\"").append(webRoot).append("/login?target=")
				.append(params.getEncodedTarget()).append("&amp;login=")
				.append(params.getEncodedLogin()).append("&amp;entityID=")
				.append(idp.getEncodedEntityID()).append("\">");
		// logo
		buffer.append("<img src=\"").append(webRoot).append("/logo/")
				.append(idp.getLogoFilename()).append("\" />");
		// display name
		buffer.append("<p>").append(idp.getEscapedDisplayName())
				.append("</p></a>");
	}

	/** Adds the HTML for the "other IdPs" button. */
	private void buildOtherIdPsButton(final StringBuilder buffer,
			final LoginParams params) {
		// link
		buffer.append("<br /><a href=\"").append(webRoot)
				.append("/discovery/full?target=")
				.append(params.getEncodedTarget()).append("&amp;login=")
				.append(params.getEncodedLogin())
				.append("\" class=\"shibboleth-discovery-others\">");
		// logo
		buffer.append("<img src=\"").append(webRoot)
				.append("/shibboleth.png\" />");
		// display name
		buffer.append("<p>").append(otherIdPsText).append("</p></a>");
	}
}
