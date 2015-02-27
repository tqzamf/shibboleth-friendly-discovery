package de.uniKonstanz.shib.disco.metadata;

import java.util.List;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class IdP {
	@JsonProperty
	public String entityID;
	@JsonProperty("DisplayNames")
	public List<TextAttr> displayNames;
	@JsonProperty("Logos")
	public List<ImageAttr> logos;

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

	public String getEntityID() {
		return entityID;
	}

	@Override
	public String toString() {
		return entityID + ": {DisplayNames: " + displayNames + ", Logos: "
				+ logos + "}";
	}
}
