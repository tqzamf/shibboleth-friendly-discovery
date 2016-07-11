package de.uniKonstanz.shib.disco.metadata;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;

import de.uniKonstanz.shib.disco.logo.LogoUpdaterThread;

/**
 * Background thread that periodically downloads the DiscoFeed and updates IdP
 * metadata from it. This also initiates the asynchronous logo download running
 * in {@link LogoUpdaterThread}.
 */
public class MetadataUpdateThread extends Thread {
	/**
	 * Metadata update interval, in seconds, used when there was no error
	 * fetching metadata.
	 */
	public static final int INTERVAL = 15 * 60;
	private static final Logger LOGGER = Logger
			.getLogger(MetadataUpdateThread.class.getCanonicalName());
	private static final DocumentBuilder DOC_BUILDER;
	static {
		final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		try {
			DOC_BUILDER = dbf.newDocumentBuilder();
		} catch (final ParserConfigurationException e) {
			throw new RuntimeException(e);
		}
	}

	private final String metadataURL;
	private final IdPMetaParser idpParser;
	private final SPMetaParser spParser;

	/**
	 * @param metadataURL
	 *            URL of Shibboleth DiscoFeed
	 * @param logoDir
	 *            logo cache directory
	 * @throws ServletException
	 *             if the logo cache directory cannot be created
	 */
	public MetadataUpdateThread(final String metadataURL, final File logoDir)
			throws ServletException {
		super("metadata updater");
		idpParser = new IdPMetaParser(logoDir);
		spParser = new SPMetaParser();
		this.metadataURL = metadataURL;
	}

	@Override
	public void run() {
		while (!interrupted()) {
			final boolean success = updateMetadata();

			try {
				if (success)
					// shibboleth generally updates its metadata every hour, so
					// it doesn't make sense to update it much more frequently.
					// rationale for 15 minutes is to not delay metadata updates
					// by another hour (worst case).
					Thread.sleep(INTERVAL * 1000);
				else
					// retry very quickly on failure. this assumes that errors
					// are caused by shibboleth restarts, but if shibboleth
					// isn't running or unreachable, the discovery is broken
					// anyway.
					Thread.sleep(1 * 60 * 1000);
			} catch (final InterruptedException e) {
				break;
			}
		}
	}

	/**
	 * Performs the metadata update.
	 * 
	 * @return <code>false</code> if metadata download fails
	 */
	private boolean updateMetadata() {
		try {
			final Document doc = DOC_BUILDER.parse(metadataURL);
			idpParser.update(doc);
			spParser.update(doc);
			return true;
		} catch (final Exception e) {
			LOGGER.log(Level.WARNING,
					"cannot update metadata; keeping existing data", e);
			return false;
		}

		// final List<IdP> idps;
		// try {
		// idps = HTTP.getJSON(metadataURL, new TypeReference<List<IdP>>() {
		// });
		// } catch (final IOException e) {
		// LOGGER.log(Level.SEVERE,
		// "failed to fetch metadata; keeping existing data", e);
		// return false;
		// }
		//
		// // convert from IdP to IdPMeta and start asynchronous logo download
		// final HashMap<String, IdPMeta> map = new HashMap<String, IdPMeta>(
		// idps.size());
		// for (final IdP idp : idps) {
		// // reuse existing metadata object if possible
		// final String entityID = idp.getEntityID();
		// final IdPMeta meta;
		// if (map != null && map.containsKey(entityID))
		// meta = map.get(entityID);
		// else
		// meta = new IdPMeta(entityID);
		//
		// final String displayName = idp
		// .getDisplayName(AbstractShibbolethServlet.LANGUAGE);
		// // meta.setDisplayName(null, displayName);
		// final String logoURL = idp.getBiggestLogo();
		// new LogoUpdaterThread(converter, meta, logoURL).start();
		// map.put(entityID, meta);
		// }
		//
		// metadata = map;
		// // pre-sort the list of all known IdPs. avoids sorting it for every
		// // request.
		// final List<IdPMeta> list = new ArrayList<IdPMeta>(map.size());
		// addMetadata(list, metadata.keySet());
		// Collections.sort(list);
		// allMetadata = list;
		// return true;
	}

	public IdPMeta getMetadata(final String entityID) {
		return idpParser.getMetadata(entityID);
	}

	public void addMetadata(final Collection<IdPMeta> list,
			final Collection<String> entities) {
		idpParser.addMetadata(list, entities);
	}

	public List<IdPMeta> getAllMetadata(final String lang) {
		return idpParser.getAllMetadata(lang);
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
}
