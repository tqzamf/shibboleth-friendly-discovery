package de.uniKonstanz.shib.disco.logo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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

@SuppressWarnings("serial")
public class LogosServlet extends AbstractShibbolethServlet {
	public static final String GENERIC_LOGO = "generic.png";
	private static final Logger LOGGER = Logger.getLogger(LogosServlet.class
			.getCanonicalName());
	private File logoCache;
	private LoadingCache<String, byte[]> cache;
	private byte[] generic;

	@Override
	public void init() throws ServletException {
		logoCache = getLogoCacheDir();
		final InputStream in = LogosServlet.class
				.getResourceAsStream(GENERIC_LOGO);
		if (in == null)
			throw new ServletException("missing generic logo");
		try {
			generic = ByteStreams.toByteArray(in);
			in.close();
		} catch (final IOException e) {
			throw new ServletException("cannot read generic logo");
		}

		// cache policy: expire logos not accessed for a day. because they are
		// updated at most ever hour, this limits the number of logos to 24 per
		// IdP, without unnecessarily expiring logos that are used frequently,
		// but only in a daily usage pattern.
		// use IdP count limit as a backup measure to avoid excessive memory
		// consumption.
		cache = CacheBuilder.newBuilder().expireAfterAccess(1, TimeUnit.DAYS)
				.maximumSize(AbstractShibbolethServlet.MAX_IDPS)
				.build(new CacheLoader<String, byte[]>() {
					@Override
					public byte[] load(final String key) {
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

		byte[] data;
		if (!filename.equals(GENERIC_LOGO))
			data = cache.getUnchecked(filename);
		else
			data = generic;
		resp.setContentType("image/png");
		resp.setContentLength(data.length);
		final ServletOutputStream output = resp.getOutputStream();
		output.write(data);
		output.close();
	}

	private byte[] getLogoData(final String info) {
		final File file = new File(logoCache, info);
		if (!file.exists() || file.length() == 0)
			// empty files are used to mark logos that exist, but cannot be
			// converted
			return generic;

		try {
			final InputStream in = new FileInputStream(file);
			final byte[] bytes = ByteStreams.toByteArray(in);
			in.close();
			return bytes;
		} catch (final IOException e) {
			LOGGER.log(Level.WARNING, "cannot read logo " + info, e);
			return generic;
		}
	}
}
