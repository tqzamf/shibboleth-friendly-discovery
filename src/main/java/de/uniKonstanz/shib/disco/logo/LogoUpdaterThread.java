package de.uniKonstanz.shib.disco.logo;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.codec.digest.DigestUtils;

import de.uniKonstanz.shib.disco.metadata.IdPMeta;
import de.uniKonstanz.shib.disco.util.HTTP;

/**
 * Performs asynchronous loading and conversion of IdP logos. If anything goes
 * wrong, it just doesn't set the logo at all, which effectively means keeping
 * the previous logo.
 * <p>
 * These threads aren't accounted for in any place, so they might leak when the
 * servlet is shut down or reloaded. This is not a long-term memory leak: they
 * don't stay around for more than a few minutes even in the worst case, because
 * logo download will abort after a few minutes, and the actual logo conversion
 * is very fast.
 */
public class LogoUpdaterThread extends AbstractLogoConverter {
	private static final Logger LOGGER = Logger
			.getLogger(LogoUpdaterThread.class.getCanonicalName());
	/**
	 * Maximum acceptable filesize for an IdP logo. Anything larger is assumed
	 * to be an error, either on the server, or by the operator.
	 */
	private static final int MAX_LOGO_SIZE = 250000;

	private final String url;
	private final IdPMeta meta;

	/**
	 * @param converter
	 *            {@link LogoConverter} to use
	 * @param meta
	 *            {@link IdPMeta} whose logo will be delay-loaded
	 * @param url
	 *            logo source URL. if <code>null</code>, will always generate a
	 *            "carpet"
	 */
	public LogoUpdaterThread(final File logoDir, final IdPMeta meta,
			final String url) {
		super(logoDir, "logo for " + meta.getEntityID() + ": " + url);
		this.meta = meta;
		this.url = url;
	}

	@Override
	public void run() {
		try {
			final String filename = convertLogo();
			// keep previous logo if download or conversion fails
			if (filename != null)
				meta.setLogoFilename(filename);
		} catch (final IOException e) {
			LOGGER.log(Level.WARNING, "cannot convert " + url, e);
		}
	}

	/**
	 * Downloads and converts a logo.
	 * 
	 * @return the new logo filename, or <code>null</code> if download fails and
	 *         there is no cached logo
	 * 
	 * @throws IOException
	 *             if writing to the {@link #logoDir} fails
	 */
	private String convertLogo() throws IOException {
		final long beforeDownload = System.currentTimeMillis();
		final File nameCache = new File(logoDir, meta.getEntityHash()
				+ ".latest");
		final Date modified = nameCacheLastModified(nameCache);
		final byte[] bytes = readLogo(url, modified);

		final String filename;
		if (bytes != null) {
			// successfully downloaded new logo. convert it and update name
			// cache.
			filename = DigestUtils.shaHex(bytes) + ".png";
			if (!convertLogo(bytes, filename))
				return null;
			writeNameCache(filename, nameCache, beforeDownload);
			return filename;
		}

		if (nameCache.exists())
			// logo hasn't changed, so just get the output filename from name
			// cache instead
			return readNameCache(nameCache);
		// no logo, no cache. there's nothing more we can do.
		return null;
	}

	private Date nameCacheLastModified(final File nameCache) {
		if (nameCache.exists() && nameCache.length() == 0) {
			// corrupt file
			nameCache.delete();
			return null;
		}

		if (nameCache.exists())
			return new Date(nameCache.lastModified());
		// no cache file. force download regardless of last modification time.
		return null;
	}

	private void writeNameCache(final String filename, final File cache,
			final long timestamp) throws IOException {
		final File temp = new File(cache.getParentFile(), cache.getName()
				+ ".temp");
		// make sure it's not a hardlink / symlink somewhere
		temp.delete();
		// write data and backdate the file to before the actual download took
		// place
		final FileWriter out = new FileWriter(temp);
		out.write(filename);
		out.close();
		temp.setLastModified(timestamp);
		// try to atomically overwrite the output file
		if (temp.renameTo(cache))
			return;

		// if that didn't work, try again after getting rid of the output file
		cache.delete();
		if (temp.renameTo(cache))
			return;

		// cannot cache name. this is bad for performance, but won't hurt
		// anything
		LOGGER.log(Level.WARNING, "cannot rename " + temp.getAbsolutePath()
				+ " to " + cache.getAbsolutePath());
		// cleanup
		temp.delete();
	}

	private String readNameCache(final File file) throws IOException {
		try (BufferedReader in = new BufferedReader(new FileReader(file))) {
			return in.readLine();
		}
	}

	private boolean convertLogo(final byte[] bytes, final String filename) {
		final File file = new File(logoDir, filename);
		if (file.exists() && file.length() > 0)
			// we've seen this exact (binary-identical) file before. converting
			// it again will not produce any materially different output.
			// empty files mark unsuccessful conversion; non-empty files contain
			// results of successful conversions.
			return file.length() > 0;

		final BufferedImage image;
		try {
			image = convertLogo(bytes);
		} catch (final InconvertibleLogoException e) {
			try {
				// logo cannot be converted. trying again on the same, binary-
				// identical logo won't produce a different result, so we mark
				// the logo as unconvertable by creating an empty file.
				new FileOutputStream(file).close();
			} catch (final IOException e1) {
				LOGGER.log(Level.WARNING,
						"cannot create flag file " + file.getAbsolutePath(), e1);
			}
			return false;
		}

		// write result to PNG, using a temp file to avoid incomplete files.
		try {
			writeTo(image, file);
			return true;
		} catch (final IOException e) {
			LOGGER.log(Level.WARNING, "cannot write " + file.getAbsolutePath());
			return false;
		}
	}

	private static BufferedImage convertLogo(final byte[] bytes)
			throws InconvertibleLogoException {
		try {
			return whiteToTransparency(bytes);
		} catch (final OutOfMemoryError e) {
			// probably caused by excessive image size. retrying probably won't
			// work.
			throw new InconvertibleLogoException("out of memory", e);
		} catch (final InconvertibleLogoException e) {
			// don't wrap that one in another exception...
			throw e;
		} catch (final Throwable e) {
			// note: catching everything here. whiteToTransparency has no
			// external dependencies and cannot leave anything in an
			// inconsistent state, so it is better to isolate errors within that
			// method than to crash the entire servlet.
			throw new InconvertibleLogoException("cannot convert: "
					+ e.getClass().getCanonicalName() + ": " + e.getMessage(),
					e);
		}
	}

	/**
	 * Downloads the logo, with strict timeouts. Retries over HTTP if HTTPS
	 * fails due to IdP operator incompetence. If the logo didn't change since
	 * last time we downloaded it, or there were {@link IOException}s, returns
	 * <code>null</code>.
	 * 
	 * @param url
	 *            source URL
	 * @param lastMod
	 *            time of last successful logo download
	 * @return logo as a byte array, or <code>null</code>
	 * 
	 */
	private static byte[] readLogo(final String url, final Date lastMod) {
		try {
			return HTTP.getBytes(url, MAX_LOGO_SIZE, lastMod);
		} catch (final IOException e) {
			// short warning; logos are somewhat expected to be unavailable
			// "just occasionally"
			LOGGER.warning("read error for " + url + ": "
					+ e.getClass().getCanonicalName() + ": " + e.getMessage());
		}

		// if SSL fails for some reason, try again over plain old insecure HTTP.
		// for an image, this shouldn't pose a significant security risk: the
		// logo can be substituted with something embarrassing, but no important
		// data will be leaked or at risk.
		if (url.toLowerCase().startsWith("https://")) {
			final String insecure = "http://" + url.substring(8);
			try {
				return HTTP.getBytes(insecure, MAX_LOGO_SIZE, lastMod);
			} catch (final IOException httpException) {
				// if HTTP download doesn't work, that's ok: the logo was
				// declared as https after all. therefore, silently swallow the
				// error for the fallback. the error for the original SSL
				// request has already been reported anyway.
			}
		}

		// give up
		return null;
	}
}
