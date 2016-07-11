package de.uniKonstanz.shib.disco.logo;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.uniKonstanz.shib.disco.metadata.IdPMeta;

/**
 * Asynchronously creates Generates a fallback "logo" from the hashed entityID,
 * writes it to a file in {@link #logoDir} named after a hash of the entityID,
 * and sets that filename as the {@link IdPMeta}'s fallback logo. The generated
 * logo is unique with high probability; the filename is guaranteed to be
 * unique.
 * <p>
 * These threads aren't accounted for in any place; they are shoot-and-forget.
 * But they finish extremely quickly so they shouldn't leak any resources.
 */
public class FallbackLogoThread extends AbstractLogoConverter {
	private static final Logger LOGGER = Logger
			.getLogger(FallbackLogoThread.class.getCanonicalName());
	private static final IdentIcon ident = new IdentIcon(74, 2, 1);

	private final IdPMeta meta;

	/**
	 * @param converter
	 *            {@link LogoConverter} to use
	 * @param meta
	 *            {@link IdPMeta} whose logo will be delay-created
	 */
	public FallbackLogoThread(final File logoDir, final IdPMeta meta) {
		super(logoDir, "fallback for " + meta.getEntityID());
		this.meta = meta;
	}

	@Override
	public void run() {
		try {
			final File file = new File(logoDir, meta.getFallbackLogo());
			if (file.exists() && file.length() == 0)
				// file exists, but is empty. try to recreate it.
				file.delete();

			if (!file.exists()) {
				final BufferedImage img = ident.getSymmetricalIcon(meta
						.getEntityHash());
				final BufferedImage transp = whiteToTransparency(img);
				writeTo(transp, file);
			}
		} catch (final IOException e) {
			// fallback logo generation is pretty failsafe; if anything does go
			// wrong, that's a major issue → long warning
			LOGGER.log(Level.WARNING, "cannot generate fallback icon for "
					+ meta.getEntityID(), e.getCause());
		}
	}
}
