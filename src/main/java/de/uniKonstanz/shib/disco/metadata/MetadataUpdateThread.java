package de.uniKonstanz.shib.disco.metadata;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamSource;

import org.w3c.dom.Document;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import de.uniKonstanz.shib.disco.loginlogger.LoginParams;
import de.uniKonstanz.shib.disco.logo.LogoUpdaterThread;
import de.uniKonstanz.shib.disco.util.HTTP;

/**
 * Background thread that periodically downloads the DiscoFeed and updates IdP
 * metadata from it. This also initiates the asynchronous logo download running
 * in {@link LogoUpdaterThread}.
 */
public class MetadataUpdateThread extends Thread {
	private static final String DISCO_FEED = "DiscoFeed";
	/**
	 * Metadata update interval, in seconds, used when there was no error
	 * fetching metadata.
	 */
	public static final int INTERVAL = 15 * 60;
	private static final Logger LOGGER = Logger
			.getLogger(MetadataUpdateThread.class.getCanonicalName());

	private final String metadataURL;
	private final IdPMetaParser idpParser;
	private final SPMetaParser spParser;
	private LoadingCache<String, IdPFilter> filters;
	private Transformer trafo;

	/**
	 * @param metadataURL
	 *            URL of Shibboleth XML metadata
	 * @param logoDir
	 *            logo cache directory
	 * @throws ServletException
	 *             if the logo cache directory cannot be created
	 */
	public MetadataUpdateThread(final String metadataURL, final File logoDir)
			throws ServletException {
		super("metadata updater");
		this.metadataURL = metadataURL;
		idpParser = new IdPMetaParser(logoDir);
		spParser = new SPMetaParser();

		// load metadata by XSLT-transforming it, and then manually parsing the
		// resulting temporary XML format. this is ridiculous, but massively
		// (~20x) faster than directly running XPath queries against the
		// original document. apparently, the Java XPath parser is just
		// extremely slow on a DOM tree...
		final StreamSource xslt = new StreamSource(
				MetadataUpdateThread.class.getResourceAsStream("metadata.xslt"));
		try {
			trafo = TransformerFactory.newInstance().newTransformer(xslt);
		} catch (final TransformerConfigurationException e) {
			throw new ServletException("failed to compile XSLT", e);
		}

		// cache filters for 4 days so they can make it over a weekend without
		// expiring. because they handle updates themselves, this doesn't mean
		// data will stay constant for 4 days, but that stale data will be
		// available as a fallback for 4 days, allowing much shorter timeouts.
		// there is at most one value per SP, and it's just a moderately sized
		// HashSet each time; soft references are used as a fallback.
		filters = CacheBuilder.newBuilder().expireAfterAccess(4, TimeUnit.DAYS)
				.softValues().build(new CacheLoader<String, IdPFilter>() {
					@Override
					public IdPFilter load(final String url) throws SQLException {
						return new IdPFilter(MetadataUpdateThread.this, url);
					}
				});
	}

	@Override
	public void run() {
		Date lastDownload = null;
		while (!interrupted()) {
			final long now = System.currentTimeMillis();
			final boolean success = updateMetadata(lastDownload);

			try {
				if (success) {
					// remember not to download it again...
					lastDownload = new Date(now);
					// shibboleth generally updates its metadata every hour, so
					// it doesn't make sense to update it much more frequently.
					// rationale for 15 minutes is to not delay metadata updates
					// by another hour (worst case).
					Thread.sleep(INTERVAL * 1000);
				} else
					// retry very quickly on failure. this assumes that all
					// errors are caused by short-term problems on the metadata
					// server, but if it's down anyway, bombarding it with
					// requests will not do much extra harm.
					Thread.sleep(1 * 60 * 1000);
			} catch (final InterruptedException e) {
				break;
			}
		}
	}

	/**
	 * Performs the metadata update.
	 * 
	 * @param lastModified
	 *            timestamp of last successful metadata download
	 * 
	 * @return <code>false</code> if metadata download fails
	 */
	private boolean updateMetadata(final Date lastModified) {
		try {
			final Document doc = HTTP.getXML(metadataURL, trafo, lastModified);
			if (doc == null) {
				LOGGER.log(Level.INFO, "metadata not modified");
				return true;
			}
			idpParser.update(doc);
			spParser.update(doc);
			LOGGER.log(Level.INFO,
					"metadata update successful; " + idpParser.getNumIdPs()
							+ " IdPs, " + spParser.getNumSPs() + " SPs");
			return true;
		} catch (final Exception e) {
			LOGGER.log(Level.WARNING,
					"cannot update metadata; keeping existing data", e);
			return false;
		}
	}

	public IdPMeta getMetadata(final String entityID) {
		return idpParser.getMetadata(entityID);
	}

	public void addMetadata(final Collection<IdPMeta> list,
			final Collection<String> entities) {
		idpParser.addMetadata(list, entities);
	}

	public List<IdPMeta> getAllMetadata(final String lang,
			final LoginParams params) {
		final List<IdPMeta> list = idpParser.getAllMetadata(lang);
		final Collection<IdPMeta> filter = getFilter(params);
		// no filter, so just return the entire list as-is
		if (filter == null)
			return list;

		// only keep IdPs that the SP actually accepts for login
		final ArrayList<IdPMeta> res = new ArrayList<IdPMeta>(list.size());
		for (final IdPMeta idp : list)
			if (filter.contains(idp))
				res.add(idp);
		return res;
	}

	public boolean isValidResponseLocation(final String entityID) {
		return spParser.isValidResponseLocation(entityID);
	}

	public boolean isValidResponseLocation(final String entityID,
			final String url) {
		return spParser.isValidResponseLocation(entityID, url);
	}

	public String getDefaultResponseLocation(final String entityID) {
		return spParser.getDefaultResponseLocation(entityID);
	}

	public Collection<IdPMeta> getFilter(final LoginParams params) {
		final String ret = params.getReturnLocation();
		if (ret == null)
			return null;

		final URL url;
		try {
			url = new URL(new URL(ret), DISCO_FEED);
		} catch (final MalformedURLException e) {
			LOGGER.log(Level.WARNING, "illegal return URL " + ret, e);
			return null;
		}

		final String filter = url.toExternalForm();
		try {
			return filters.get(filter).getIdPs();
		} catch (final ExecutionException e) {
			LOGGER.log(Level.WARNING, "cannot get filter " + filter, e);
			return null;
		}
	}
}
