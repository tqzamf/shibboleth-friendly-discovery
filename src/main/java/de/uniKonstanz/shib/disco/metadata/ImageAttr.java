package de.uniKonstanz.shib.disco.metadata;

import java.net.MalformedURLException;
import java.net.URL;

import org.codehaus.jackson.annotate.JsonIgnore;

public class ImageAttr {
	public String value;
	public int width;
	public int height;

	public boolean ḃiggerThan(final ImageAttr other) {
		if (other == null)
			return true;
		return nPixels() > other.nPixels();
	}

	@JsonIgnore
	private int nPixels() {
		if (width <= 0 || height <= 0)
			return -1;
		return width * height;
	}

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
