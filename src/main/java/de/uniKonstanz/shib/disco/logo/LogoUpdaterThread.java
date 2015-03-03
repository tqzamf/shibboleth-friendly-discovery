package de.uniKonstanz.shib.disco.logo;

import java.io.File;

import de.uniKonstanz.shib.disco.metadata.IdPMeta;

/**
 * Performs asynchronous loading and conversion of IdP logos.
 * 
 * These threads aren't accounted for in any place, so they might leak when the
 * servlet is shut down or reloaded. This is not a long-term memory leak: they
 * don't stay around for more than a few minutes even in the worst case, because
 * logo download will abort after a few minutes, and the actual logo conversion
 * is very fast.
 */
public class LogoUpdaterThread extends Thread {
	private final String url;
	private final IdPMeta target;
	private final LogoConverter converter;

	/**
	 * @param converter
	 *            {@link LogoConverter} to use
	 * @param target
	 *            {@link IdPMeta} whose logo will be delay-loaded
	 * @param url
	 *            logo source URL
	 */
	public LogoUpdaterThread(final LogoConverter converter,
			final IdPMeta target, final String url) {
		super("logo updater for " + target.getEntityID() + ": " + url);
		this.converter = converter;
		this.target = target;
		this.url = url;
	}

	@Override
	public void run() {
		// try to read and convert the IdP-supplied logo
		if (url != null) {
			final File file = converter.getLogo(url);
			if (file != null) {
				setLogo(file);
				return;
			}
		}

		// else generate a fallback icon as a random "carpet" based on the
		// entityID
		final File fallback = converter.getFallbackLogo(target.getEntityID());
		if (fallback != null)
			setLogo(fallback);
	}

	private void setLogo(final File file) {
		target.setLogoFilename(file.getName());
	}
}
