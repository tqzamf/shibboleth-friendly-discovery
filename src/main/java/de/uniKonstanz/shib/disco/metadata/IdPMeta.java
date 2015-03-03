package de.uniKonstanz.shib.disco.metadata;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import com.google.common.escape.Escaper;
import com.google.common.html.HtmlEscapers;

import de.uniKonstanz.shib.disco.logo.LogoUpdaterThread;

/**
 * Data class to represent an IdP in memory. Holds entityID, display name and
 * logo; compares case-insensitively according to display name.
 */
public class IdPMeta implements Comparable<IdPMeta> {
	private static final Escaper HTML_ESCAPER = HtmlEscapers.htmlEscaper();
	private final String entityID;
	private final String displayName;
	private String logo;
	private final String encEntityID;
	private final String escDisplayName;

	public IdPMeta(final String entityID, final String displayName,
			final String logo) {
		this.entityID = entityID;
		final String normalizedDisplayName = displayName
				.replaceAll("\\s+", " ").trim();
		this.logo = logo;
		try {
			encEntityID = URLEncoder.encode(entityID, "UTF-8");
		} catch (final UnsupportedEncodingException e) {
			throw new RuntimeException("no UTF-8 support", e);
		}
		this.displayName = normalizedDisplayName.toLowerCase();
		escDisplayName = HTML_ESCAPER.escape(normalizedDisplayName);
	}

	/**
	 * Gets the display name, safely escaped for literal inclusion in HTML
	 * documents. Both single and double quotes are escaped.
	 * 
	 * @return the escaped display name
	 */
	public String getEscapedDisplayName() {
		return escDisplayName;
	}

	/**
	 * Gets the plain entityID. This is unsafe to include in query parameters.
	 * 
	 * @return the entityID
	 */
	public String getEntityID() {
		return entityID;
	}

	/**
	 * Gets the URL-encoded entityID. It is safely escaped for inclusion in
	 * query parameters.
	 * 
	 * @return the entityID
	 */
	public String getEncodedEntityID() {
		return encEntityID;
	}

	/**
	 * Gets the filename of the logo. The filename is unescaped but doesn't
	 * contain any non-alphanumeric characters (except for the {@code .png}
	 * extension).
	 * 
	 * @return filename of the logo
	 */
	public String getLogoFilename() {
		return logo;
	}

	/**
	 * Sets the filename of the logo. To be called by {@link LogoUpdaterThread}.
	 * 
	 * @param logo
	 *            the new logo filename, including extension
	 */
	public void setLogoFilename(final String logo) {
		this.logo = logo;
	}

	/** Compares by display name. */
	public int compareTo(final IdPMeta other) {
		// display name is already kept in lowercase for this comparison
		return displayName.compareTo(other.displayName);
	}

	@Override
	public String toString() {
		return entityID + ": " + displayName + "; " + logo;
	}
}
