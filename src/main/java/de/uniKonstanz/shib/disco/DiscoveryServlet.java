package de.uniKonstanz.shib.disco;

import java.io.IOException;
import java.io.PrintWriter;
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
		wayf = getResourceAsString("wayf.html");
		bookmarkNotice = getResourceAsString("bookmark-notice.html");

		db = getDatabaseConnection();
		ranking = new IdPRanking(db, numTopIdPs);
		meta = new MetadataUpdateThread(discoFeed, getLogoCacheDir());
		meta.start();
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

		resp.setContentType("text/html");
		final PrintWriter out = resp.getWriter();
		out.write(buffer.toString());
		out.close();
	}

	private void buildEmbeddedDiscovery(final HttpServletRequest req,
			final HttpServletResponse resp, final LoginParams params)
			throws IOException {
		final List<IdPMeta> idps = getIdPList(req, resp);

		final StringBuilder buffer = new StringBuilder();
		buffer.append(jsHeader);
		buffer.append("shibbolethDiscovery('").append(webRoot).append("','");
		buildNotices(buffer, params);
		for (final IdPMeta idp : idps)
			buildHTML(buffer, idp, params);
		buildOtherIdPsButton(buffer, params);
		buffer.append("');");

		resp.setContentType("text/javascript");
		final PrintWriter out = resp.getWriter();
		out.write(buffer.toString());
		out.close();
	}

	private void buildFriendlyDiscovery(final HttpServletRequest req,
			final HttpServletResponse resp, final LoginParams params)
			throws IOException {
		final List<IdPMeta> idps = getIdPList(req, resp);
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

		resp.setContentType("text/html");
		final PrintWriter out = resp.getWriter();
		out.write(buffer.toString());
		out.close();
	}

	private void buildNotices(final StringBuilder buffer,
			final LoginParams params) {
		buffer.append(wayf);
		if (params.canBookmark())
			buffer.append(bookmarkNotice);
	}

	private List<IdPMeta> getIdPList(final HttpServletRequest req,
			final HttpServletResponse resp) {
		final LinkedHashSet<IdPMeta> list = new LinkedHashSet<IdPMeta>(
				numTopIdPs);
		addCookieFavorite(list, req);
		addNethashFavorites(list, req);
		if (list.size() < numTopIdPs)
			// don't unnecessarily add global favorites if there are already
			// enough nethash-local favorites.
			addGlobalFavorites(list);

		// limit to correct number of IdPs, and convert to an actual List
		final ArrayList<IdPMeta> res = new ArrayList<IdPMeta>(numTopIdPs);
		for (final IdPMeta idp : list) {
			res.add(idp);
			if (res.size() >= numTopIdPs)
				break;
		}
		return res;
	}

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
				if (idp != null)
					list.add(idp);
			} catch (final Throwable t) {
				LOGGER.info("malformed cookie: " + c.getValue());
				// bad cookie; ignored
			}
		}
	}

	private void addNethashFavorites(final Collection<IdPMeta> list,
			final HttpServletRequest req) {
		final List<String> entities = ranking
				.getIdPList(getClientNetworkHash(req));
		if (entities != null)
			meta.addMetadata(list, entities);
	}

	private void addGlobalFavorites(final LinkedHashSet<IdPMeta> list) {
		final List<String> entities = ranking.getGlobalIdPList();
		if (entities != null)
			meta.addMetadata(list, entities);
	}

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

	@Override
	public void destroy() {
		meta.interrupt();
		db.close();
	}
}
