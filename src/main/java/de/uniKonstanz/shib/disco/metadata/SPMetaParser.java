package de.uniKonstanz.shib.disco.metadata;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

class SPMetaParser extends XPMetaParser {
	private static final Logger LOGGER = Logger.getLogger(SPMetaParser.class
			.getCanonicalName());

	private HashMap<String, SPMeta> metadata;

	@Override
	public void update(final Document doc) {
		final Element root = doc.getDocumentElement();
		if (!root.getTagName().equals("Metadata"))
			throw new IllegalArgumentException("invalid root tag "
					+ root.getTagName());

		final HashMap<String, SPMeta> map = new HashMap<String, SPMeta>();
		for (final Element node : getChildren(root, "SP")) {
			// collect all DiscoveryResponse URLs and order them as specified in
			// the metadata.
			final List<String> responses = new ArrayList<String>();
			int bestIndex = Integer.MAX_VALUE;
			boolean bestDefault = false;
			String defaultLocation = null;
			for (final Element disco : getChildren(node, "ReponseLocation")) {
				// check that location is a valid URL without any query string
				final String location = disco.getTextContent();
				try {
					final URL url = new URL(location);
					if (url.getQuery() != null) {
						LOGGER.log(Level.INFO, "ignoring location '" + location
								+ "' due to query string");
						continue;
					}
				} catch (final MalformedURLException e) {
					LOGGER.log(Level.INFO, "ignoring invalid location '"
							+ location + "'", e);
					continue;
				}

				// check index is non-negative integer (as per standard)
				final String strIndex = disco.getAttribute("index");
				final int index;
				try {
					index = Integer.parseInt(strIndex);
				} catch (final NumberFormatException e) {
					LOGGER.log(Level.INFO, "ignoring non-numeric index '"
							+ strIndex + "'", e);
					continue;
				}
				if (index < 0) {
					LOGGER.log(Level.INFO, "negative index " + index + " for "
							+ location);
					continue;
				}

				// good response location
				responses.add(location);

				// determine default response location. this picks the
				// lowest-index entry marked as default. if no entry is
				// marked as default, this just picks the lowest-index
				// entry.
				// in most cases, there is exactly one entry, which isn't
				// marked as default. so while there is no official default, the
				// response location is obvious. the rule below allows omitting
				// the response location for these SPs as well.
				final boolean isDefault = disco.getAttribute("isDefault")
						.equalsIgnoreCase("true");
				if (isDefault || !bestDefault)
					if (index < bestIndex) {
						bestIndex = index;
						bestDefault = isDefault;
						defaultLocation = location;
					}
			}

			// completely ignores SPs that have no discovery reponses. the
			// discovery refuses to redirect to anything not explicitly
			// whitelisted, so those SPs cannot be used anyway.
			if (!responses.isEmpty()) {
				final String entityID = node.getAttribute("entityID");
				// reuse existing metadata object if possible
				final SPMeta meta;
				if (metadata == null || !metadata.containsKey(entityID))
					meta = new SPMeta(entityID);
				else
					meta = metadata.get(entityID);
				map.put(entityID, meta);

				// update values
				meta.setReturnLocations(responses);
				meta.setDefaultReturn(defaultLocation);
			}
		}
		metadata = map;
	}

	public boolean isValidResponseLocation(final String entityID) {
		final HashMap<String, SPMeta> map = metadata;
		if (map == null)
			return false;
		return map.containsKey(entityID);
	}

	public boolean isValidResponseLocation(final String entityID,
			final String url) {
		final HashMap<String, SPMeta> map = metadata;
		if (map == null)
			return false;
		if (!map.containsKey(entityID))
			return false;

		// check that the URL matches one of the acceptable response locations,
		// ignoring the query string in the URL.
		final List<String> list = map.get(entityID).getReturnLocations();
		for (final String i : list) {
			if (matches(url, i))
				return true;
		}
		return false;
	}

	private boolean matches(final String url, final String pattern) {
		// URL must start with the pattern ...
		if (!url.startsWith(pattern))
			return false;

		// ... and be followed by either nothing or a query string
		if (url.length() == pattern.length())
			return true;
		if (url.charAt(pattern.length()) == '?')
			return true;

		// everything else is not a valid match
		return false;
	}

	public String getDefaultResponseLocation(final String entityID) {
		final HashMap<String, SPMeta> map = metadata;
		if (map == null)
			return null;
		if (!map.containsKey(entityID))
			return null;
		return map.get(entityID).getDefaultReturn();
	}

	public int getNumSPs() {
		final Map<String, SPMeta> meta = metadata;
		return meta != null ? meta.size() : 0;
	}
}
