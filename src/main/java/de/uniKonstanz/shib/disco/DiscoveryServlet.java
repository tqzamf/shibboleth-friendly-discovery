package de.uniKonstanz.shib.disco;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.net.URLEncoder;
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
import de.uniKonstanz.shib.disco.loginlogger.LoginServlet;
import de.uniKonstanz.shib.disco.metadata.IdPMeta;
import de.uniKonstanz.shib.disco.metadata.MetadataUpdateThread;
import de.uniKonstanz.shib.disco.util.ReconnectingDatabase;

@SuppressWarnings("serial")
public class DiscoveryServlet extends AbstractShibbolethServlet {
	static final Logger LOGGER = Logger.getLogger(DiscoveryServlet.class
			.getCanonicalName());
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

	@Override
	public void init() throws ServletException {
		webRoot = getWebRoot();
		discoFeed = getContextParameter("shibboleth.discofeed.url");
		numTopIdPs = Integer
				.parseInt(getContextParameter("discovery.friendly.idps"));
		otherIdPsText = getContextParameter("discovery.friendly.others");
		defaultTarget = getDefaultTarget();
		jsHeader = getResourceAsString("header.js");
		friendlyHeader = getResourceAsString("friendly-header.html");
		fullHeader = getResourceAsString("full-header.html");
		footer = getResourceAsString("footer.html");

		db = getDatabaseConnection();
		ranking = new IdPRanking(db, numTopIdPs);
		meta = new MetadataUpdateThread(discoFeed, getLogoCacheDir());
		meta.start();
	}

	@Override
	protected void doGet(final HttpServletRequest req,
			final HttpServletResponse resp) throws ServletException,
			IOException {
		// get target attribute, or use default. if none given and no default,
		// that's a fatal error; we cannot recover from that.
		String target = req.getParameter("target");
		if (target == null)
			target = defaultTarget;
		if (target == null) {
			LOGGER.warning("discovery request without target attribute from "
					+ req.getRemoteAddr() + "; sending error");
			resp.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED,
					"missing request attribute: target");
			return;
		}
		// redirect to full discovery if unspecified
		resp.setCharacterEncoding(ENCODING);
		final String encodedTarget = URLEncoder.encode(target, ENCODING);
		final String pi = req.getPathInfo();
		if (pi == null || pi.equals("/")) {
			resp.sendRedirect(webRoot + "/discovery/full?target="
					+ encodedTarget);
			return;
		}

		//
		if (pi.equalsIgnoreCase("/full"))
			buildFullDiscovery(req, resp, encodedTarget);
		else if (pi.equalsIgnoreCase("/embed"))
			buildEmbeddedDiscovery(req, resp, encodedTarget);
		else if (pi.equalsIgnoreCase("/friendly"))
			buildFriendlyDiscovery(req, resp, encodedTarget);
		else
			resp.sendError(HttpServletResponse.SC_NOT_FOUND,
					"Discovery service " + pi.substring(1) + " does not exist.");
	}

	private void buildFullDiscovery(final HttpServletRequest req,
			final HttpServletResponse resp, final String encodedTarget)
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
		for (final IdPMeta idp : idps)
			buildHTML(buffer, idp, encodedTarget);
		buffer.append(footer);

		resp.setContentType("text/html");
		final PrintWriter out = resp.getWriter();
		out.write(buffer.toString());
		out.close();
	}

	private void buildEmbeddedDiscovery(final HttpServletRequest req,
			final HttpServletResponse resp, final String encodedTarget)
			throws IOException {
		final List<IdPMeta> idps = getIdPList(req, resp);

		final StringBuilder buffer = new StringBuilder();
		buffer.append(jsHeader);
		buffer.append("shibbolethDiscovery('").append(webRoot).append("','");
		for (final IdPMeta idp : idps)
			buildHTML(buffer, idp, encodedTarget);
		buildOthers(buffer, encodedTarget);
		buffer.append("');");

		resp.setContentType("text/javascript");
		final PrintWriter out = resp.getWriter();
		out.write(buffer.toString());
		out.close();
	}

	private void buildFriendlyDiscovery(final HttpServletRequest req,
			final HttpServletResponse resp, final String encodedTarget)
			throws IOException {
		final List<IdPMeta> idps = getIdPList(req, resp);
		if (idps.isEmpty()) {
			// in the (unlikely) case that none of the entityIDs are known,
			// fall back to providing the complete list. this is more useful
			// than providing just the "others" button.
			buildFullDiscovery(req, resp, encodedTarget);
			return;
		}

		final StringBuilder buffer = new StringBuilder();
		buffer.append(friendlyHeader);
		for (final IdPMeta idp : idps)
			buildHTML(buffer, idp, encodedTarget);
		buildOthers(buffer, encodedTarget);
		buffer.append(footer);

		resp.setContentType("text/html");
		final PrintWriter out = resp.getWriter();
		out.write(buffer.toString());
		out.close();
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
			final String encodedTarget) {
		// link
		buffer.append("<a href=\"").append(webRoot).append("/login?target=")
				.append(encodedTarget).append("&amp;entityID=")
				.append(idp.getEncodedEntityID()).append("\">");
		// logo
		buffer.append("<img src=\"").append(webRoot).append("/logo/")
				.append(idp.getLogoFilename()).append("\" />");
		// display name
		buffer.append("<p>").append(idp.getEscapedDisplayName())
				.append("</p></a>");
	}

	private void buildOthers(final StringBuilder buffer,
			final Object encodedTarget) {
		// link
		buffer.append("<a href=\"").append(webRoot)
				.append("/discovery/full?target=").append(encodedTarget)
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
