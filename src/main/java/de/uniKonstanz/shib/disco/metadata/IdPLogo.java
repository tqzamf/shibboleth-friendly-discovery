package de.uniKonstanz.shib.disco.metadata;

public class IdPLogo {
	private final int width;
	private final int height;
	private final String filename;

	public IdPLogo(final String filename, final int width, final int height) {
		this.width = width;
		this.height = height;
		this.filename = filename;
	}
}
