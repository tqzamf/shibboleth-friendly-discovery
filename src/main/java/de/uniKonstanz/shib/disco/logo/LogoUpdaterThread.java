package de.uniKonstanz.shib.disco.logo;

import java.io.File;

import de.uniKonstanz.shib.disco.metadata.IdPMeta;

public class LogoUpdaterThread extends Thread {
	private final String url;
	private final IdPMeta target;
	private final LogoConverter converter;

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
		target.setLogo(file.getName());
	}
}
