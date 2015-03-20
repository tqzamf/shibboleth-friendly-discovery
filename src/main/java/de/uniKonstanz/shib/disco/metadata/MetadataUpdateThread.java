package de.uniKonstanz.shib.disco.metadata;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import org.codehaus.jackson.type.TypeReference;

import de.uniKonstanz.shib.disco.AbstractShibbolethServlet;
import de.uniKonstanz.shib.disco.logo.LogoConverter;
import de.uniKonstanz.shib.disco.logo.LogoUpdaterThread;
import de.uniKonstanz.shib.disco.logo.LogosServlet;
import de.uniKonstanz.shib.disco.util.HTTP;

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
	private final String feedURL;
	private final LogoConverter converter;
	private Map<String, IdPMeta> metadata;
	private List<IdPMeta> allMetadata;

	/**
	 * @param discoFeed
	 *            URL of Shibboleth DiscoFeed
	 * @param logoDir
	 *            logo cache directory
	 * @throws ServletException
	 *             if the logo cache directory cannot be created
	 */
	public MetadataUpdateThread(final String discoFeed, final File logoDir)
			throws ServletException {
		super("metadata updater");
		feedURL = discoFeed;
		converter = new LogoConverter(logoDir);
		logoDir.mkdirs();
		if (!logoDir.isDirectory())
			throw new ServletException("cannot create "
					+ logoDir.getAbsolutePath());
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
		final List<IdP> idps;
		try {
			idps = HTTP.getJSON(feedURL, new TypeReference<List<IdP>>() {
			});
		} catch (final IOException e) {
			LOGGER.log(Level.SEVERE,
					"failed to fetch metadata; keeping existing data", e);
			return false;
		}

		// convert from IdP to IdPMeta and start asynchronous logo download
		final HashMap<String, IdPMeta> map = new HashMap<String, IdPMeta>();
		for (final IdP idp : idps) {
			final String entityID = idp.getEntityID();
			final String logoURL = idp.getBiggestLogo();
			final String logo;
			if (map != null && map.containsKey(entityID))
				// keep previous logo if possible, to avoid the generic logo
				// flickering in.
				logo = map.get(entityID).getLogoFilename();
			else
				logo = LogosServlet.GENERIC_LOGO;
			final String displayName = idp
					.getDisplayName(AbstractShibbolethServlet.LANGUAGE);
			final IdPMeta meta = new IdPMeta(entityID, displayName, logo);
			new LogoUpdaterThread(converter, meta, logoURL).start();
			map.put(entityID, meta);
		}

		metadata = map;
		// pre-sort the list of all known IdPs. avoids sorting it for every
		// request.
		final List<IdPMeta> list = new ArrayList<IdPMeta>(map.size());
		addMetadata(list, metadata.keySet());
		Collections.sort(list);
		allMetadata = list;
		return true;
	}

	/**
	 * Gets metadata for a particular IdP, given its entityID. Returns
	 * <code>null</code> if this IdP is unknown.
	 * 
	 * @param entityID
	 *            identifies the IdP
	 * @return corresponding {@link IdPMeta} object, or <code>null</code>
	 */
	public IdPMeta getMetadata(final String entityID) {
		final Map<String, IdPMeta> map = metadata;
		if (map == null)
			return null;
		return map.get(entityID);
	}

	/**
	 * Obtains metadata for a list of IdP, identified by their entityIDs.
	 * 
	 * @param list
	 *            return value; entries are appended to this list
	 * @param entities
	 *            list of entityIDs for IdPs
	 */
	public void addMetadata(final Collection<IdPMeta> list,
			final Collection<String> entities) {
		final Map<String, IdPMeta> map = metadata;
		if (map == null)
			return;

		for (final String entityID : entities) {
			final IdPMeta meta = map.get(entityID);
			if (meta != null)
				list.add(meta);
			else
				LOGGER.warning("cannot find metadata for " + entityID);
		}
	}

	/**
	 * Obtains a sorted list of all known IdPs. Never returns <code>null</code>,
	 * but may return an empty list.
	 * 
	 * @return sorted list of {@link IdPMeta}s
	 */
	public List<IdPMeta> getAllMetadata() {
		if (allMetadata == null)
			return Collections.emptyList();
		return allMetadata;
	}
}
