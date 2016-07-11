package de.uniKonstanz.shib.disco.logo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import javax.imageio.ImageIO;

/**
 * Converts logos for use in the IdP buttons. This means scaling them to a
 * constant size and replacing white backggrounds with transparency. If a logo
 * cannot be downloaded, uses {@link IdentIcon} to generate a random unique logo
 * from the hashed entityID.
 */
public class AbstractLogoConverter extends Thread {
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

	protected final File logoDir;

	/**
	 * @param logoDir
	 *            logo cache directory where files are created
	 * @param threadName
	 *            name of thread (for identification only)
	 */
	public AbstractLogoConverter(final File logoDir, final String threadName) {
		super("logo converter: " + threadName);
		this.logoDir = logoDir;
	}

	/**
	 * Replaces white pixels with transparency.
	 * 
	 * @param bytes
	 *            input image as a byte array
	 * @return
	 * @throws IOException
	 *             if writing fails
	 * @throws InconvertibleLogoException
	 *             if the input cannot be read as an image
	 */
	protected static BufferedImage whiteToTransparency(final byte[] bytes)
			throws IOException, InconvertibleLogoException {
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

		return whiteToTransparency(image);
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
	protected static BufferedImage whiteToTransparency(final BufferedImage image) {
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
	protected static void writeTo(final BufferedImage buffer,
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
			// it is ok for rename to fail as long as the output file exists:
			// files are named according to their contents so if the name is the
			// same, so is the contents.
			throw new IOException("cannot rename " + temp.getAbsolutePath()
					+ " to " + outputFile.getAbsolutePath());
		temp.delete();
	}
}
