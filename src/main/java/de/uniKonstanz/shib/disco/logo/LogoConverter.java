package de.uniKonstanz.shib.disco.logo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import org.apache.commons.codec.digest.DigestUtils;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import de.uniKonstanz.shib.disco.util.HTTP;

/**
 * Converts logos for use in the IdP buttons. This means scaling them to a
 * constant size and replacing white backggrounds with transparency. If a logo
 * cannot be downloaded, uses {@link IdentIcon} to generate a random unique logo
 * from the hashed entityID.
 */
public class LogoConverter {
	private static final Logger LOGGER = Logger.getLogger(LogoConverter.class
			.getCanonicalName());
	/**
	 * Maximum acceptable filesize for an IdP logo. Anything larger is assumed
	 * to be an error, either on the server, or by the operator.
	 */
	private static final int MAX_LOGO_SIZE = 500000;
	/**
	 * Output logo width, without 1px border. If the aspect ratio changes, the
	 * CSS has to be adjusted.
	 */
	private static final int LOGO_WIDTH = 300 - 2;
	/**
	 * Output logo height, without 1px border. If the aspect ratio changes, the
	 * CSS has to be adjusted.
	 */
	private static final int LOGO_HEIGHT = 150 - 2;
	private final File logoDir;
	private final LoadingCache<String, File> cache;
	private IdentIcon ident;

	/**
	 * @param logoDir
	 *            logo cache directory where files are created
	 */
	public LogoConverter(final File logoDir) {
		this.logoDir = logoDir;
		// cache URL-to-filename mapping for a few hours. this avoids
		// downloading the logo more than a few times a day, only to check that
		// it's still the same file. this means that changing the IdP logo can
		// take a while to apply, but those logos shouldn't change frequently in
		// any case.
		// no maximum size. there is just one element per IdP, and it's just a
		// small filename.
		cache = CacheBuilder.newBuilder().expireAfterAccess(6, TimeUnit.HOURS)
				.build(new CacheLoader<String, File>() {
					@Override
					public File load(final String key) throws IOException,
							InconvertibleLogoException {
						return convertLogo(key);
					}
				});
		ident = new IdentIcon(37, 4, 2);
	}

	/**
	 * Tries to read and convert the logo from the given URL, writing the
	 * resulting file into {@link #logoDir} named after a hash of its contents,
	 * and returning it as a {@link File}. Returns <code>null</code> if anything
	 * goes wrong.
	 * 
	 * @param url
	 *            URL to load the logo from
	 * @return the filename relative to {@link #logoDir}, or <code>null</code>
	 */
	public File getLogo(final String url) {
		try {
			return cache.get(url);
		} catch (final ExecutionException e) {
			if (e.getCause() instanceof InconvertibleLogoException)
				// abbreviated warning for trouble with the logos. these are
				// somewhat expected to occasionally be nonexistent or broken.
				LOGGER.info("cannot convert " + url + ": "
						+ e.getClass().getCanonicalName() + ": "
						+ e.getMessage());
			else
				// other exceptions definitely shouldn't happen → long warning
				LOGGER.log(Level.WARNING, "cannot convert " + url, e.getCause());
			return null;
		}
	}

	/**
	 * Generates a "logo" from the hashed entityID, writes it to a file in
	 * {@link #logoDir} named after a hash of the entityID, and returns that
	 * file as a {@link File}. The generated logo is unique with overwhelming
	 * probability.
	 * 
	 * @param entityID
	 *            entityID of the IdP
	 * @return the filename relative to {@link #logoDir}, or <code>null</code>
	 *         if there is a serious error
	 */
	public File getFallbackLogo(final String entityID) {
		try {
			final String checksum = DigestUtils.shaHex(entityID);
			final File file = new File(logoDir, "i" + checksum + ".png");
			if (file.exists() && file.length() > 0)
				return file;
			if (file.exists())
				// file exists, but is empty. try to recreate it.
				file.delete();

			final BufferedImage img = ident.getSymmetricalIcon(checksum);
			final BufferedImage transp = whiteToTransparency(img);
			writeTo(transp, file);
			return file;
		} catch (final IOException e) {
			// fallback logo generation is pretty failsafe; if anything does go
			// wrong, that's a major issue → long warning
			LOGGER.log(Level.WARNING, "cannot generate fallback icon for "
					+ entityID, e.getCause());
			return null;
		}
	}

	/**
	 * Downloads and converts a logo.
	 * 
	 * @param url
	 *            source URL
	 * @return {@link File} that the result was written to
	 * @throws IOException
	 *             if writing to the {@link #logoDir} fails
	 * @throws InconvertibleLogoException
	 *             if download or conversion of the logo fails
	 */
	private File convertLogo(final String url) throws IOException,
			InconvertibleLogoException {
		final byte[] bytes = readLogo(url);
		final String checksum = DigestUtils.shaHex(bytes);
		final File file = new File(logoDir, checksum + ".png");
		if (file.exists() && file.length() > 0)
			return file;
		if (file.exists())
			// file exist, but is empty. this marks an inconvertible logo, so we
			// have to use the generic one instead.
			throw new InconvertibleLogoException("known to be inconvertible: "
					+ url);

		try {
			whiteToTransparency(bytes, file);
			return file;
		} catch (final InconvertibleLogoException e) {
			markInconvertible(file);
			throw e;
		} catch (final OutOfMemoryError e) {
			// probably caused by excessive image size. retrying probably won't
			// work.
			markInconvertible(file);
			throw new InconvertibleLogoException("out of memory converting "
					+ url, e);
		} catch (final IOException e) {
			// serious trouble, eg. permission problems on logo cache directory.
			throw new RuntimeException("cannot write output file", e);
		} catch (final Throwable e) {
			// note: catching everything here. whiteToTransparency has no
			// external dependencies and cannot leave anything in an
			// inconsistent state, so it is better to isolate errors within that
			// method than to crash the entire servlet.
			throw new IOException("cannot convert " + url + ": "
					+ e.getClass().getCanonicalName() + ": " + e.getMessage(),
					e);
		}
	}

	/**
	 * Downloads the logo, with strict timeouts. Retries over HTTP if HTTPS
	 * fails due to IdP operator incompetence.
	 * 
	 * @param url
	 *            source URL
	 * @return logo as a byte array
	 * @throws InconvertibleLogoException
	 *             if download fails
	 */
	private static byte[] readLogo(final String url)
			throws InconvertibleLogoException {
		try {
			return HTTP.getBytes(url, MAX_LOGO_SIZE);
		} catch (final IOException e) {
			if (!url.toLowerCase().startsWith("https://"))
				throw new InconvertibleLogoException("cannot read " + url, e);

			// if SSL fails for some reason, try again over plain old insecure
			// HTTP. for an image, this shouldn't pose a significant security
			// risk: the logo can be substituted with something embarrassing,
			// but no important data will be leaked or at risk.
			final String insecure = "http://" + url.substring(8);
			try {
				final byte[] bytes = HTTP.getBytes(insecure, MAX_LOGO_SIZE);
				// if HTTP works but HTTPS doesn't, warn about the misconfigured
				// server
				LOGGER.warning("SSL error for " + url + ": "
						+ e.getClass().getCanonicalName() + ": "
						+ e.getMessage());
				return bytes;
			} catch (final IOException httpException) {
				// if HTTP doesn't work, that's ok: the logo was declared as
				// https after all. therefore, report the error for the original
				// SSL connection instead of the HTTP fallback.
				throw new InconvertibleLogoException("cannot read " + url, e);
			}
		}
	}

	/**
	 * Creates an empty flag file to mark a logo as inconvertible.
	 * 
	 * @param file
	 *            file to generate
	 */
	private static void markInconvertible(final File file) {
		try {
			// logo cannot be converted. trying again on the same,
			// binary-identical logo won't produce a different result, so we
			// mark the logo as unconvertable by creating an empty file.
			new FileOutputStream(file).close();
		} catch (final IOException e1) {
			LOGGER.log(Level.WARNING,
					"cannot create flag file " + file.getAbsolutePath(), e1);
		}
	}

	/**
	 * Replaces white pixels with transparency.
	 * 
	 * @param bytes
	 *            input image as a byte array
	 * @param outputFile
	 *            {@link File} to write the result
	 * @throws IOException
	 *             if writing fails
	 * @throws InconvertibleLogoException
	 *             if the input cannot be read as an image
	 */
	private static void whiteToTransparency(final byte[] bytes,
			final File outputFile) throws IOException,
			InconvertibleLogoException {
		final BufferedImage image;
		try {
			image = ImageIO.read(new ByteArrayInputStream(bytes));
		} catch (final IOException e) {
			throw new RuntimeException(
					"ByteArrayInputStream throwing IOExceptions!?");
		}
		if (image == null) {
			final byte[] sample;
			if (bytes.length >= 20) {
				sample = new byte[20];
				System.arraycopy(bytes, 0, sample, 0, 20);
			} else
				sample = bytes;
			throw new InconvertibleLogoException("cannot load as image: "
					+ Arrays.toString(sample));
		}

		final BufferedImage buffer = whiteToTransparency(image);
		// write result to PNG, using a temp file to avoid incomplete files.
		writeTo(buffer, outputFile);
	}

	/**
	 * Replaces white pixels with transparency.
	 * 
	 * @param image
	 *            {@link BufferedImage} containing the input image, in any color
	 *            space
	 * @return {@link BufferedImage} containing the input image, in ARGB color
	 *         space ({@link BufferedImage#TYPE_INT_ARGB}).
	 */
	private static BufferedImage whiteToTransparency(final BufferedImage image) {
		// calculate scaling factor to fit into a standard 275x150 rectangle
		final float fX = image.getWidth() / (float) LOGO_WIDTH;
		final float fY = image.getHeight() / (float) LOGO_HEIGHT;
		float f = fX;
		if (fY > fX)
			f = fY;
		final int width = (int) (LOGO_WIDTH * fX / f);
		final int height = (int) (LOGO_HEIGHT * fY / f);
		final int left = (LOGO_WIDTH - width) / 2;
		final int top = (LOGO_HEIGHT - height) / 2;
		// perform scaling, convert to ARGB color space, and add a 1px space
		// around everything, to work around stupid scaling bugs in "some
		// browsers" (Firefox).
		final BufferedImage buffer = new BufferedImage(LOGO_WIDTH + 2,
				LOGO_HEIGHT + 2, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g2d = buffer.createGraphics();
		g2d.setBackground(Color.white);
		g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g2d.clearRect(0, 0, LOGO_WIDTH + 2, LOGO_HEIGHT + 2);
		g2d.drawImage(image, left + 1, top + 1, width, height, null);
		g2d.dispose();

		// convert white to transparency. this involves rescaling the color
		// of semi-transparent pixels, to account for the fact that they
		// will be multiplied with alpha before rendering. without this,
		// mid-tone grays would look washed-out.
		final int[] pixels = buffer.getRaster().getPixels(0, 0, LOGO_WIDTH + 2,
				LOGO_HEIGHT + 2, (int[]) null);
		for (int i = 0; i < pixels.length; i += 4) {
			final int r = pixels[i + 0];
			final int g = pixels[i + 1];
			final int b = pixels[i + 2];
			int trans = r;
			if (g < trans)
				trans = g;
			if (b < trans)
				trans = b;
			final int alpha = 255 - trans;
			pixels[i + 3] = alpha;
			if (alpha != 0) {
				pixels[i + 0] = 255 * (r - trans) / alpha;
				pixels[i + 1] = 255 * (g - trans) / alpha;
				pixels[i + 2] = 255 * (b - trans) / alpha;
			}
		}
		buffer.getRaster().setPixels(0, 0, LOGO_WIDTH + 2, LOGO_HEIGHT + 2,
				pixels);
		return buffer;
	}

	/**
	 * Writes a {@link BufferedImage} to a file. Makes sure that all parent
	 * directories exist.
	 * 
	 * @param buffer
	 *            {@link BufferedImage} to write
	 * @param outputFile
	 *            {@link File} to create or overwrite
	 * @throws IOException
	 *             on failure
	 */
	private static void writeTo(final BufferedImage buffer,
			final File outputFile) throws IOException {
		// make sure parent directory exists. expected to fail almost always
		// because the directory already exists.
		outputFile.getParentFile().mkdirs();
		final File temp = new File(outputFile.getParentFile(),
				outputFile.getName() + ".tmp");
		temp.delete();
		if (!ImageIO.write(buffer, "png", temp))
			throw new IOException("cannot convert to PNG");
		if (temp.renameTo(outputFile))
			return;
		if (!outputFile.exists())
			throw new IOException("cannot rename " + temp.getAbsolutePath()
					+ " to " + outputFile.getAbsolutePath());
		temp.delete();
	}
}
