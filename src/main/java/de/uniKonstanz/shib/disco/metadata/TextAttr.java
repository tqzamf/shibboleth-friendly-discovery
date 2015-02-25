package de.uniKonstanz.shib.disco.metadata;

public class TextAttr {
	public String value;
	public String lang;

	public boolean isLanguage(final String language) {
		if (lang == null)
			return false;
		return lang.toLowerCase().startsWith(language);
	}

	public boolean isEmpty() {
		return value == null || value.isEmpty();
	}

	@Override
	public String toString() {
		return lang + ": " + value;
	}
}
