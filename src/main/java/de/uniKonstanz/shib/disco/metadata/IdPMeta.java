package de.uniKonstanz.shib.disco.metadata;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import com.google.common.escape.Escaper;
import com.google.common.html.HtmlEscapers;

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
		this.displayName = displayName.replaceAll("\\s+", " ").trim();
		this.logo = logo;
		try {
			encEntityID = URLEncoder.encode(entityID, "UTF-8");
		} catch (final UnsupportedEncodingException e) {
			throw new RuntimeException("no UTF-8 support", e);
		}
		escDisplayName = HTML_ESCAPER.escape(displayName);
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getEscapedDisplayName() {
		return escDisplayName;
	}

	public String getEntityID() {
		return entityID;
	}

	public String getEncodedEntityID() {
		return encEntityID;
	}

	public String getLogoFilename() {
		return logo;
	}

	public int compareTo(final IdPMeta other) {
		return displayName.compareTo(other.displayName);
	}

	@Override
	public String toString() {
		return entityID + ": " + displayName + "; " + logo;
	}

	public void setLogo(final String logo) {
		this.logo = logo;
	}
}
