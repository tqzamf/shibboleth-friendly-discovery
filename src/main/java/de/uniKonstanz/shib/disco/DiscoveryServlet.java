package de.uniKonstanz.shib.disco;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
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
import de.uniKonstanz.shib.disco.util.ConnectionPool;

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
	private MetadataUpdateThread metaUpdate;
	private ConnectionPool db;
	private String metadataURL;
	private String header1;
	private String header2;
	private String footer;
	private IdPRanking ranking;
	private int numTopIdPs;
	private String jsHeader;
	private String wayf;
	private String noIdPsError;

	@Override
	public void init() throws ServletException {
		super.init();
		metadataURL = getContextParameter("shibboleth.metadata.url");
		numTopIdPs = Integer
				.parseInt(getContextParameter("discovery.friendly.idps"));

		jsHeader = getResourceAsString("header.js");
		header1 = getResourceAsString("header1.html");
		header2 = getResourceAsString("header2.html");
		footer = getResourceAsString("footer.html");
		wayf = normalize(getResourceAsString("wayf.html"));
		noIdPsError = getResourceAsString("no-idps.html");

		// start MetadataUpdateThread and make it available to LoginServlet
		metaUpdate = new MetadataUpdateThread(metadataURL, getLogoCacheDir());
		metaUpdate.start();
		getServletContext().setAttribute(
				MetadataUpdateThread.class.getCanonicalName(), metaUpdate);
		db = getDatabaseConnectionPool();
		ranking = new IdPRanking(db, metaUpdate);
	}

	@Override
	public void destroy() {
		super.destroy();
		getServletContext().removeAttribute(
				MetadataUpdateThread.class.getCanonicalName());
		metaUpdate.interrupt();
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
		final LoginParams params = parseLoginParams(req, resp);
		if (params == null)
			return;

		// special case: if isPassive is set, simply redirect to the cookie
		// favorite. if there isn't any, we're supposed to return without a
		// entityID parameter.
		if (params.isPassive()) {
			final String idpEntityID = getCookieFavorite(req);
			final String encodedEntityID;
			if (idpEntityID != null)
				encodedEntityID = URLEncoder.encode(idpEntityID, ENCODING);
			else
				encodedEntityID = null;
			sendRedirectToShibboleth(resp, params, encodedEntityID);
			return;
		}

		// redirect to full discovery if no discovery flavor specified
		final String pi = req.getPathInfo();
		if (pi == null || pi.equals("/")) {
			sendRedirectToFullDiscovery(resp, params);
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
		final List<IdPMeta> idps = metaUpdate.getAllMetadata(DEFAULT_LANGUAGE);
		if (idps.isEmpty()) {
			// if there are no valid IdPs, the user cannot log in. there is no
			// point in showing an empty discovery page; just report an error
			// instead. make sure the error message doesn't get cached, though.
			LOGGER.warning("no IdPs available for discovery!");
			setCacheHeaders(resp, 0);
			final StringBuilder buffer = new StringBuilder();
			buffer.append(noIdPsError);
			buffer.append("Metadata not available, "
					+ "or metadata contains no identity providers.");
			buffer.append(footer);
			sendResponse(resp, buffer, "text/html");
			return;
		}

		final StringBuilder buffer = new StringBuilder();
		buffer.append(header1);
		buffer.append("var shibbolethDiscoverySearchLimit = Number.POSITIVE_INFINITY;");
		buffer.append(header2);
		buildNotices(buffer, params);
		buildHTML(buffer, idps, params, Integer.MAX_VALUE);
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
		final Collection<IdPMeta> idps = getIdPList(req, params);
		if (idps.isEmpty()) {
			// in the (unlikely) case that none of the entityIDs are known,
			// fall back to providing the complete list. this is more useful
			// than providing just the "others" button.
			buildFullDiscovery(req, resp, params);
			return;
		}

		final StringBuilder buffer = new StringBuilder();
		buffer.append(header1);
		buffer.append("var shibbolethDiscoverySearchLimit = " + numTopIdPs
				+ ";");
		buffer.append(header2);
		buildNotices(buffer, params);
		buildHTML(buffer, idps, params, numTopIdPs);
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
		final Iterable<IdPMeta> idps = getIdPList(req, params);

		final StringBuilder buffer = new StringBuilder();
		buffer.append(jsHeader);
		buffer.append("shibbolethDiscovery('").append(webRoot)
				.append("'," + numTopIdPs + ",'");
		buildNotices(buffer, params);
		buildHTML(buffer, idps, params, numTopIdPs);
		buildOtherIdPsButton(buffer, params);
		buffer.append("<br />');");

		// page is per-user and will change if the "cookie favorite" changes, so
		// it shouldn't be cached.
		setCacheHeaders(resp, 0);
		sendResponse(resp, buffer, "text/javascript");
	}

	private void buildHTML(final StringBuilder buffer,
			final Iterable<IdPMeta> idps, final LoginParams params,
			final int limit) {
		final Collection<IdPMeta> filter = metaUpdate.getFilter(params);

		int n = 0;
		for (final IdPMeta idp : idps) {
			// only add IdPs that the SP actually accepts for login
			if (filter == null || filter.contains(idp)) {
				buildHTML(buffer, idp, params, n >= limit);
				n++;
			}
		}
	}

	/**
	 * Gets the "most likely" IdPs. These are, in order:
	 * <ol>
	 * <li>the last one the client used, if any, as marked by a cookie named
	 * {@link LoginServlet#IDP_COOKIE}
	 * <li>the most popular ones for "his" network, as defined by
	 * {@link #getClientNetworkHash(HttpServletRequest)}
	 * <li>the globally most popular ones
	 * <li>everything else
	 * </ol>
	 */
	private Collection<IdPMeta> getIdPList(final HttpServletRequest req,
			final LoginParams params) {
		// LinkedHashSet retains order, so items added first will be at the top
		// of the IdP list served to the client
		final LinkedHashSet<IdPMeta> list = new LinkedHashSet<IdPMeta>();
		addCookieFavorite(list, req);
		addNethashFavorites(list, req);
		addGlobalFavorites(list);
		addEverything(list);
		return list;
	}

	/**
	 * Checks for the {@link LoginServlet#IDP_COOKIE} cookie and, if present,
	 * inserts the indicated IdP into the list.
	 */
	private void addCookieFavorite(final Collection<IdPMeta> list,
			final HttpServletRequest req) {
		final String entityID = getCookieFavorite(req);
		final IdPMeta idp = metaUpdate.getMetadata(entityID);
		if (idp != null) {
			list.add(idp);
			return;
		}
	}

	private String getCookieFavorite(final HttpServletRequest req) {
		final Cookie[] cookies = req.getCookies();
		if (cookies == null)
			return null;

		for (final Cookie c : cookies) {
			if (c.getName() == null || c.getValue() == null)
				continue;
			if (!c.getName().equalsIgnoreCase(LoginServlet.IDP_COOKIE))
				continue;

			try {
				return URLDecoder.decode(c.getValue(), ENCODING);
			} catch (final Throwable t) {
				LOGGER.info("malformed cookie: " + c.getValue());
				// bad cookie; ignored
			}
		}
		return null;
	}

	/**
	 * Obtains the {@link #numTopIdPs} most popular IdPs for the client's
	 * nethash, and inserts them into the given list.
	 */
	private void addNethashFavorites(final Collection<IdPMeta> list,
			final HttpServletRequest req) {
		final IdPMeta[] entities = ranking
				.getIdPList(getClientNetworkHash(req));
		if (entities != null)
			for (final IdPMeta e : entities)
				list.add(e);
	}

	/**
	 * Obtains the {@link #numTopIdPs} most popular IdPs across all nethashes,
	 * and inserts them into the given list.
	 */
	private void addGlobalFavorites(final LinkedHashSet<IdPMeta> list) {
		final IdPMeta[] entities = ranking.getGlobalIdPList();
		if (entities != null)
			for (final IdPMeta e : entities)
				list.add(e);
	}

	/**
	 * Appends the entire list of IdPs. Order matters; this is called last.
	 */
	private void addEverything(final LinkedHashSet<IdPMeta> list) {
		final List<IdPMeta> entities = metaUpdate
				.getAllMetadata(DEFAULT_LANGUAGE);
		if (entities != null)
			for (final IdPMeta e : entities)
				list.add(e);
	}

	/**
	 * Adds the "Where are you from" prompt, and the
	 * "you can bookmark these links" notice if appropriate.
	 */
	private void buildNotices(final StringBuilder buffer,
			final LoginParams params) {
		buffer.append(wayf);
		// bookmark notice. always present, but change text to indicate whether
		// success can be expected.
		buffer.append("<p><a href=\"").append(webRoot)
				.append("/bookmarks.html\" target=\"_blank\">");
		if (params.canBookmark())
			buffer.append("How to bookmark these links");
		else
			buffer.append("Do not bookmark these links!");
		buffer.append("</a></p>");
	}

	/** Adds the HTML for a single IdP button. */
	private void buildHTML(final StringBuilder buffer, final IdPMeta idp,
			final LoginParams params, final boolean extra) {
		// WARNING this is directly included both as literal HTML and in a
		// single-quotes javascript string! thus, they must not include:
		// - newlines
		// - single quotes
		// - backslashes

		// link; parameters carefully encoded
		buffer.append("<a href=\"").append(webRoot).append("/login?");
		params.appendToURL(buffer, "&amp;");
		buffer.append("&amp;idpEntityID=").append(idp.getEncodedEntityID())
				.append("\" class=\"shibboleth-discovery-button\"");
		if (extra)
			buffer.append(" style=\"display:none\"");
		buffer.append('>');
		// logo; filename never contains anything unsafe
		buffer.append("<img src=\"").append(webRoot).append("/logo/")
				.append(idp.getLogoFilename()).append("\" />");
		// display name (escaped)
		buffer.append("<p>")
				.append(idp.getEscapedDisplayName(DEFAULT_LANGUAGE))
				.append("</p></a>");
	}

	/** Adds the HTML for the "other IdPs" button. */
	private void buildOtherIdPsButton(final StringBuilder buffer,
			final LoginParams params) {
		// link; parameters carefully encoded
		buffer.append("<br /><a href=\"").append(webRoot)
				.append("/discovery/full?");
		params.appendToURL(buffer, "&amp;");
		buffer.append("\" class=\"shibboleth-discovery-button\""
				+ " id=\"shibboleth-discovery-others\">");
		// logo; filename never contains anything unsafe
		buffer.append("<img src=\"").append(webRoot)
				.append("/shibboleth.png\" />");
		// other IdPs text. unescaped so it can contain HTML; that string
		// is trusted anyway.
		buffer.append("<p>full list of institutions</p></a>");
	}
}
