package de.uniKonstanz.shib.disco.metadata;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.servlet.ServletException;
import javax.xml.XMLConstants;
import javax.xml.xpath.XPathExpressionException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import de.uniKonstanz.shib.disco.AbstractShibbolethServlet;
import de.uniKonstanz.shib.disco.logo.FallbackLogoThread;
import de.uniKonstanz.shib.disco.logo.LogoUpdaterThread;

public class IdPMetaParser {
	private static final Logger LOGGER = Logger.getLogger(IdPMetaParser.class
			.getCanonicalName());
	private static final XPathNodeList IDP_NODES = new XPathNodeList(
			"/md:EntitiesDescriptor/md:EntityDescriptor[md:IDPSSODescriptor]");
	private static final XPathNodeList DISPLAYNAME_NODES = new XPathNodeList(
			"md:IDPSSODescriptor/md:Extensions/mdui:UIInfo/mdui:DisplayName");
	private static final XPathNodeList LOGO_NODES = new XPathNodeList(
			"md:IDPSSODescriptor/md:Extensions/mdui:UIInfo/mdui:Logo");

	private Map<String, IdPMeta> metadata;
	private Map<String, List<IdPMeta>> allMetadata;
	private final File logoDir;
	private final HashSet<String> suffixes;

	/**
	 * @param discoFeed
	 *            URL of Shibboleth DiscoFeed
	 * @param logoDir
	 *            logo cache directory
	 * @throws ServletException
	 *             if the logo cache directory cannot be created
	 */
	public IdPMetaParser(final File logoDir) throws ServletException {
		this.logoDir = logoDir;
		logoDir.mkdirs();
		if (!logoDir.isDirectory())
			throw new ServletException("cannot create "
					+ logoDir.getAbsolutePath());
		suffixes = new HashSet<String>();
		for (final String s : ImageIO.getReaderFileSuffixes())
			suffixes.add(s);
	}

	/**
	 * Gets metadata for a particular IdP, given its entityID. Returns
	 * <code>null</code> if this IdP is unknown.
	 * 
	 * @param entityID
	 *            identifies the IdP
	 * @return corresponding {@link IdPMeta} object, or <code>null</code>
	 */
	public IdPMeta getMetadata(final String entityID) {
		final Map<String, IdPMeta> map = metadata;
		if (map == null)
			return null;
		return map.get(entityID);
	}

	/**
	 * Obtains metadata for a list of IdP, identified by their entityIDs.
	 * 
	 * @param list
	 *            return value; entries are appended to this list
	 * @param entities
	 *            list of entityIDs for IdPs
	 */
	public void addMetadata(final Collection<IdPMeta> list,
			final Collection<String> entities) {
		final Map<String, IdPMeta> map = metadata;
		if (map == null)
			return;

		for (final String entityID : entities) {
			final IdPMeta meta = map.get(entityID);
			if (meta != null)
				list.add(meta);
			else
				LOGGER.warning("cannot find metadata for " + entityID);
		}
	}

	/**
	 * Obtains a list of all known IdPs, sorted by display name. Never returns
	 * <code>null</code>, but may return an empty list.
	 * 
	 * @param lang
	 *            preferred language (for sorting)
	 * 
	 * @return sorted list of {@link IdPMeta}s
	 */
	public List<IdPMeta> getAllMetadata(final String lang) {
		if (allMetadata == null)
			return Collections.emptyList();
		return allMetadata.get(lang);
	}

	/** Parses the XML document and starts asynchronous logo download. */
	public void update(final Document doc) throws XPathExpressionException {
		final HashMap<String, IdPMeta> map = new HashMap<String, IdPMeta>();
		final HashSet<String> languages = new HashSet<String>();

		for (final Element node : IDP_NODES.eval(doc)) {
			// reuse existing metadata object if possible
			final String entityID = node.getAttribute("entityID");
			final IdPMeta meta;
			if (metadata == null || !metadata.containsKey(entityID)) {
				meta = new IdPMeta(entityID);
				new FallbackLogoThread(logoDir, meta).start();
			} else
				meta = metadata.get(entityID);
			map.put(entityID, meta);

			try {
				updateDisplayNames(meta, node, languages);
				updateLogo(meta, node);
			} catch (final XPathExpressionException e) {
				LOGGER.log(Level.WARNING, "failed to parse metadata for "
						+ entityID + "; keeping existing data", e);
			}
		}

		// pre-sort the list of all known IdPs. avoids re-sorting it for every
		// request.
		final HashMap<String, List<IdPMeta>> all = new HashMap<String, List<IdPMeta>>();
		for (final String lang : languages) {
			final List<IdPMeta> list = new ArrayList<IdPMeta>(map.values());
			Collections.sort(list, new IdPCompatator(lang));
			all.put(lang, list);
		}
		// update state variables
		metadata = map;
		allMetadata = all;
	}

	private void updateDisplayNames(final IdPMeta meta, final Element node,
			final Set<String> languages) throws XPathExpressionException {
		// get display name in all available languages
		String defaultName = null;
		for (final Element i : DISPLAYNAME_NODES.eval(node)) {
			final String lang = i.getAttributeNS(XMLConstants.XML_NS_URI,
					"lang");
			final String displayName = i.getTextContent();
			if (displayName.trim().isEmpty())
				continue; // never useful

			// reduce to first 2 letters (ISO 2-letter code)
			final String language = lang.substring(0, 2).toLowerCase();
			if (lang.length() >= 2) {
				// only bother to add display names in valid ISO 2-letter
				// languages to the explicit list, but do consider them for the
				// default display name below.
				meta.setDisplayName(language, displayName);
				languages.add(language);
			}

			// determine name in default language
			if (AbstractShibbolethServlet.DEFAULT_LANGUAGE.equals(language))
				// found a nonempty display name in the default language; keep
				// the last one
				defaultName = displayName;
			else if (defaultName == null)
				// fallback: pick just about any display name if we don't have
				// one yet
				defaultName = displayName;
		}
		// keep previous default name if we don't have a new one
		if (defaultName != null)
			meta.setDefaultDisplayName(defaultName);
	}

	private void updateLogo(final IdPMeta meta, final Element node)
			throws XPathExpressionException {
		// find the logo with the largest declared size (number of pixels).
		// if there are no (sensible) logos, bestLogo will be null, and the
		// LogoConverter will create a random "carpet" logo.
		String bestURL = null;
		int bestPixels = -1;
		final boolean bestKnown = false;
		for (final Element i : LOGO_NODES.eval(node)) {
			try {
				final int width = Integer.parseInt(i.getAttribute("width"));
				final int height = Integer.parseInt(i.getAttribute("height"));
				final String url = i.getTextContent();
				// sanity checking
				if (width <= 0 || height <= 0 || url.isEmpty())
					continue;

				// replace previous best logo if the current one has more
				// pixels, but don't replace a smaller logo in a known-
				// convertible file format by a logo in an unknown file format.
				final int pos = url.lastIndexOf('.');
				final boolean knownFormat;
				if (pos >= 0) {
					// might contain all sorts of weirdness if the last path
					// component doesn't contain a dot, but then it won't appear
					// in the list of known suffixes anyway.
					final String suffix = url.substring(pos + 1);
					knownFormat = suffixes.contains(suffix);
				} else
					knownFormat = false;
				final int nPixels = width * height;
				if (nPixels > bestPixels && (knownFormat || !bestKnown)) {
					bestPixels = nPixels;
					bestURL = url;
				}
			} catch (final NumberFormatException e) {
				// not serious except that the IdP operator is an idiot,
				// which is always a very bad omen...
			}
		}
		// keep previous logo if we don't have a new one
		if (bestURL != null && meta.isStaleLogo())
			new LogoUpdaterThread(logoDir, meta, bestURL).start();
	}

	private final class IdPCompatator implements Comparator<IdPMeta> {
		private final String lang;

		private IdPCompatator(final String lang) {
			this.lang = lang;
		}

		@Override
		public int compare(final IdPMeta a, final IdPMeta b) {
			// sort by display name in target language, case
			// insensitively
			final String aName = a.getLowercaseDisplayName(lang);
			final String bName = b.getLowercaseDisplayName(lang);
			final int deltaName = aName.compareTo(bName);
			if (deltaName != 0)
				return deltaName;
			// make sure two different IdPs are never considered equal.
			return a.compareTo(b);
		}
	}
}
