package de.uniKonstanz.shib.disco.metadata;

import java.net.MalformedURLException;
import java.net.URL;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;

/**
 * Data class to hold information about a declared logo when deserializing the
 * DiscoFeed from JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageAttr {
	@JsonProperty
	public String value;
	@JsonProperty
	public int width;
	@JsonProperty
	public int height;

	/**
	 * Compares logos by their declared size. This may differ significantly from
	 * the actual size due to IdP operator incompetence, but it's the best we
	 * can do without actually downloading the image.
	 * 
	 * @param other
	 *            the {@link ImageAttr} to compare to; can be <code>null</code>
	 * @return true if this {@link ImageAttr} is larger than the other, or if
	 *         the other {@link ImageAttr} is <code>null</code>
	 */
	@JsonIgnore
	public boolean ḃiggerThan(final ImageAttr other) {
		if (other == null)
			return true;
		return nPixels() > other.nPixels();
	}

	/**
	 * Computes the declared number of pixels for a logo.
	 * 
	 * @return number of pixels according to metadata, or -1 if incorrect
	 */
	@JsonIgnore
	private int nPixels() {
		if (width <= 0 || height <= 0)
			return -1;
		return width * height;
	}

	/**
	 * Checks if the logo URL is valid.
	 * 
	 * @return <code>true</code> if valid
	 */
	@JsonIgnore
	public boolean isValid() {
		if (value == null || value.isEmpty())
			return false;

		try {
			new URL(value);
			return true;
		} catch (final MalformedURLException e) {
			return false;
		}
	}

	@Override
	public String toString() {
		return value + ": " + width + "x" + height;
	}
}
