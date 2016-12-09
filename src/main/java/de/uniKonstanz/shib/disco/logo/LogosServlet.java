package de.uniKonstanz.shib.disco.logo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.io.ByteStreams;

import de.uniKonstanz.shib.disco.AbstractShibbolethServlet;

/**
 * Serves logos from the logo cache directory, caching them in memory to reduce
 * I/O overhead. Files persist until they aren't accessed for a day, but IdP
 * logos are named after a hash of the file contents, so if the logo for an IdP
 * changes, that change will be visible immediately.
 * 
 * Also note that there is no automatic cleanup of the logo cache directory,
 * because it isn't expected to fill too quickly. Nevertheless, emptying it
 * about once a year may be a good idea.
 */
@SuppressWarnings("serial")
public class LogosServlet extends AbstractShibbolethServlet {
	public static final Logger LOGGER = Logger.getLogger(LogosServlet.class
			.getCanonicalName());
	/** Filename of the generic logo in the logo "directory". */
	public static final String GENERIC_LOGO = "generic.png";
	public File logoCache;
	private LoadingCache<String, byte[]> cache;
	private byte[] generic;

	@Override
	public void init() throws ServletException {
		super.init();
		logoCache = getLogoCacheDir();
		generic = getResource("generic.png");

		// cache policy: expire logos not accessed for a day. because they are
		// updated at most ever hour, this limits the number of logos to 24 per
		// IdP, without unnecessarily expiring logos that are used frequently,
		// but only in a daily usage pattern.
		// uses soft values as a backup measure to avoid excessive memory
		// consumption. the average logo is ~12KB; this could start adding up.
		cache = CacheBuilder.newBuilder().expireAfterAccess(1, TimeUnit.DAYS)
				.softValues().build(new CacheLoader<String, byte[]>() {
					@Override
					public byte[] load(final String key) throws IOException {
						return getLogoData(key);
					}
				});
	}

	@Override
	protected void doGet(final HttpServletRequest req,
			final HttpServletResponse resp) throws ServletException,
			IOException {
		final String info = req.getPathInfo();
		if (info == null || !info.endsWith(".png")) {
			resp.sendError(HttpServletResponse.SC_FORBIDDEN,
					"Permission Denied");
			return;
		}
		final String filename = info.substring(1);
		if (filename.contains("/")) {
			resp.sendError(HttpServletResponse.SC_FORBIDDEN,
					"Permission Denied");
			return;
		}

		// logos are immutable. therefore, if this is a cache revalidation, just
		// return 304 immediately.
		if (req.getHeaders("If-Modified-Since").hasMoreElements()) {
			resp.sendError(HttpServletResponse.SC_NOT_MODIFIED);
			return;
		}

		byte[] data;
		if (!filename.equals(GENERIC_LOGO))
			data = getLogo(filename);
		else
			data = generic;
		// logo files are named according to their contents, so they never
		// change. allow client to cache them forever.
		if (data != null)
			setCacheHeaders(resp, Integer.MAX_VALUE);

		// if a named logo is unavailable, substitute the generic logo, but
		// prevent that from being cached.
		if (data == null) {
			setUncacheable(resp);
			data = generic;
		}
		resp.setContentType("image/png");
		resp.setContentLength(data.length);
		final ServletOutputStream output = resp.getOutputStream();
		output.write(data);
		output.close();
	}

	/**
	 * Obtains a logo from the cache, reading it from disk if necessary. If the
	 * file doesn't exist, returns the generic logo instead.
	 * 
	 * @param filename
	 *            filename of the logo
	 * @return the logo as a byte array, or {@code null} on failure
	 */
	private byte[] getLogo(final String filename) {
		try {
			return cache.get(filename);
		} catch (final ExecutionException e) {
			LOGGER.log(Level.WARNING, "cannot read logo " + filename, e);
			return generic;
		}
	}

	/**
	 * Reads a logo from disk.
	 * 
	 * @param filename
	 *            filename of the logo
	 * @return the logo as a byte array
	 * @throws IOException
	 *             if the file doesn't exist or cannot be read
	 */
	protected byte[] getLogoData(final String filename) throws IOException {
		final File file = new File(logoCache, filename);
		if (!file.exists() || file.length() == 0)
			// empty files are used to mark logos that exist, but cannot be
			// converted
			return generic;

		final InputStream in = new FileInputStream(file);
		final byte[] bytes = ByteStreams.toByteArray(in);
		in.close();
		return bytes;
	}
}
