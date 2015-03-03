package de.uniKonstanz.shib.disco.metadata;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;

/**
 * Data class to hold information about a declared display name when
 * deserializing the DiscoFeed from JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TextAttr {
	@JsonProperty
	public String value;
	@JsonProperty
	public String lang;

	/**
	 * Checks if this {@link TextAttr} is in the given language, or a dialect (
	 * {@code en_US}) thereof.
	 * 
	 * @param language
	 *            the language to check, lowercase
	 * @return <code>true</code> if the language matches
	 */
	@JsonIgnore
	public boolean isLanguage(final String language) {
		if (lang == null)
			return false;
		return lang.toLowerCase().startsWith(language);
	}

	/**
	 * Checks if this display name is empty. A display name which only contains
	 * whitespace is considered empty.
	 * 
	 * @return <code>true</code> if empty
	 */
	@JsonIgnore
	public boolean isEmpty() {
		return value == null || value.replaceAll("\\s", "").isEmpty();
	}

	@Override
	public String toString() {
		return lang + ": " + value;
	}
}
