package de.uniKonstanz.shib.disco.metadata;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.codec.digest.DigestUtils;

import com.google.common.escape.Escaper;
import com.google.common.escape.Escapers;
import com.google.common.html.HtmlEscapers;

import de.uniKonstanz.shib.disco.AbstractShibbolethServlet;
import de.uniKonstanz.shib.disco.logo.FallbackLogoThread;
import de.uniKonstanz.shib.disco.logo.LogoUpdaterThread;

/**
 * Data class to represent an IdP in memory. Holds entityID, display name and
 * logo; compares case-insensitively according to display name.
 */
public class IdPMeta extends XPMeta<IdPMeta> implements Comparable<IdPMeta> {
	/**
	 * Like {@link HtmlEscapers#htmlEscaper()}, but include the backslash as
	 * well so that the resulting text can be included in a single-quoted
	 * javascript string without further escaping.
	 */
	private static final Escaper HTML_ESCAPER = Escapers.builder()
			.addEscape('"', "&quot;").addEscape('\'', "&#39;")
			.addEscape('\\', "&#92;").addEscape('&', "&amp;")
			.addEscape('<', "&lt;").addEscape('>', "&gt;").build();
	private static final String DEFAULT_DISPLAY_NAME_KEY = null;
	private static final long LOGO_SHELF_LIFE = 1000 * 60 * 60 * 2;

	private final Map<String, String> lcDisplayNames = new HashMap<String, String>();
	private final Map<String, String> escDisplayNames = new HashMap<String, String>();
	private final String fallbackLogo;
	private String logo;
	private long lastLogoUpdate;

	public IdPMeta(final String entityID) {
		super(entityID);

		// dummy initial values
		setDisplayName(DEFAULT_DISPLAY_NAME_KEY, entityID);
		fallbackLogo = "i" + getEntityHash() + ".png";
	}

	/**
	 * Gets the display name, safely escaped for literal inclusion in HTML
	 * documents. Both single and double quotes are escaped. Picks the
	 * {@link AbstractShibbolethServlet#DEFAULT_LANGUAGE} if the preferred
	 * language isn't available.
	 * 
	 * @param languages
	 *            list of preferred languages, in order
	 * 
	 * @return the escaped display name
	 */
	public String getEscapedDisplayName(final Iterable<String> languages) {
		final Map<String, String> names = escDisplayNames;
		// try to find name in the best language we have
		for (final String lang : languages)
			if (names.containsKey(lang))
				return names.get(lang);
		// else fall back to the default display name, in whatever language it
		// may happen to be
		return names.get(DEFAULT_DISPLAY_NAME_KEY);
	}

	/**
	 * Gets the display name in lowercase (for comparison). Unsafe for inclusion
	 * in HTML. Picks the {@link AbstractShibbolethServlet#DEFAULT_LANGUAGE} if
	 * the preferred language isn't available.
	 * 
	 * @param lang
	 *            preferred language
	 * 
	 * @return the escaped display name
	 */
	public String getLowercaseDisplayName(final String lang) {
		final String lcDisplayName = lcDisplayNames.get(lang);
		if (lcDisplayName != null)
			return lcDisplayName;
		return lcDisplayNames.get(DEFAULT_DISPLAY_NAME_KEY);
	}

	/**
	 * Changes the display name.
	 * 
	 * @param lang
	 *            language tag for the name
	 * @param displayName
	 *            the new, raw display name
	 */
	public void setDisplayName(final String lang, final String displayName) {
		final String normalizedDisplayName = displayName
				.replaceAll("\\s+", " ").trim();

		lcDisplayNames.put(lang, normalizedDisplayName.toLowerCase());
		escDisplayNames.put(lang, HTML_ESCAPER.escape(normalizedDisplayName));
	}

	/**
	 * Changes the display name in the
	 * {@link AbstractShibbolethServlet#DEFAULT_LANGUAGE}.
	 * 
	 * @param displayName
	 *            the new, raw display name in the default language
	 */
	public void setDefaultDisplayName(final String displayName) {
		setDisplayName(DEFAULT_DISPLAY_NAME_KEY, displayName);
	}

	/**
	 * Gets the filename of the logo. The filename is unescaped but doesn't
	 * contain any non-alphanumeric characters (except for the {@code .png}
	 * extension).
	 * 
	 * @return filename of the logo
	 */
	public String getLogoFilename() {
		if (logo != null)
			return logo;
		return fallbackLogo;
	}

	/**
	 * Sets the filename of the logo. To be called by {@link LogoUpdaterThread}.
	 * 
	 * @param logo
	 *            the new logo filename, including extension
	 */
	public void setLogoFilename(final String logo) {
		this.logo = logo;
		lastLogoUpdate = System.currentTimeMillis();
	}

	/**
	 * Check whether the logo is stale and should be downloaded again.
	 * 
	 * @return <code>true</code> if the logo is older than
	 *         {@link #LOGO_SHELF_LIFE}
	 */
	public boolean isStaleLogo() {
		final long delta = System.currentTimeMillis() - lastLogoUpdate;
		return delta > LOGO_SHELF_LIFE;
	}

	/**
	 * Gets the filename of the fallback logo. Intended for
	 * {@link FallbackLogoThread}; for everything else,
	 * {@link #getLogoFilename()} automatically decides which logo to return.
	 * 
	 * @return filename of the fallback logo
	 */
	public String getFallbackLogo() {
		return fallbackLogo;
	}

	/**
	 * Gets the hashed entity ID, used for generating the fallback logo.
	 * 
	 * @return SHA1 of the entityID
	 */
	public String getEntityHash() {
		return DigestUtils.shaHex(entityID);
	}

	@Override
	public String toString() {
		return super.toString() + ": "
				+ lcDisplayNames.get(DEFAULT_DISPLAY_NAME_KEY) + "; " + logo;
	}
}
