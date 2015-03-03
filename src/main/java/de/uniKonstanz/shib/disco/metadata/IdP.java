package de.uniKonstanz.shib.disco.metadata;

import java.util.List;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;

/**
 * Data class to hold IdP metadata deserialized form the JSON DiscoFeed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IdP {
	@JsonProperty
	public String entityID;
	@JsonProperty("DisplayNames")
	public List<TextAttr> displayNames;
	@JsonProperty("Logos")
	public List<ImageAttr> logos;

	/**
	 * Gets the logo with the largest declared size, if there are any logos.
	 * 
	 * @return the largest logo, or <code>null</code> if none
	 */
	@JsonIgnore
	public String getBiggestLogo() {
		if (logos != null && !logos.isEmpty()) {
			// try to find the biggest valid logo.
			ImageAttr biggest = null;
			for (final ImageAttr i : logos)
				if (i.ḃiggerThan(biggest) && i.isValid())
					biggest = i;
			if (biggest != null)
				return biggest.value;
		}

		// no valid logos declared
		return null;
	}

	/**
	 * Gets the display name in the given language, or in just about any
	 * language if none is declared in the requested language. If no display
	 * name is declared at all, uses the entityID instead.
	 * 
	 * @param language
	 *            requested language for display name, lowercase
	 * @return the display name, or <code>null</code> if none
	 */
	@JsonIgnore
	public String getDisplayName(final String language) {
		if (displayNames != null) {
			// try to find a nonempty display name in the target language
			for (final TextAttr i : displayNames)
				if (i.isLanguage(language) && !i.isEmpty())
					return i.value;

			// try to find any nonempty display name
			for (final TextAttr i : displayNames)
				if (!i.isEmpty())
					return i.value;
		}

		// no display name available; just use the entityID instead.
		// this is easier to debug than a missing IdP.
		return entityID;
	}

	@JsonIgnore
	public String getEntityID() {
		return entityID;
	}

	@Override
	public String toString() {
		return entityID + ": {DisplayNames: " + displayNames + ", Logos: "
				+ logos + "}";
	}
}
