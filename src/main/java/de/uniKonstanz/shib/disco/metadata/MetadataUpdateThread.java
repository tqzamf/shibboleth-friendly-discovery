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

public class MetadataUpdateThread extends Thread {
	private static final Logger LOGGER = Logger
			.getLogger(MetadataUpdateThread.class.getCanonicalName());
	private final String feedURL;
	private final LogoConverter converter;
	private Map<String, IdPMeta> metadata;

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
					Thread.sleep(15 * 60 * 1000);
				else
					Thread.sleep(1 * 60 * 1000);
			} catch (final InterruptedException e) {
				break;
			}
		}
	}

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
		return true;
	}

	public IdPMeta getMetadata(final String entityID) {
		final Map<String, IdPMeta> map = metadata;
		if (map == null)
			return null;
		return map.get(entityID);
	}

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

	public List<IdPMeta> getAllMetadata() {
		if (metadata == null)
			return Collections.emptyList();

		final List<IdPMeta> list = new ArrayList<IdPMeta>();
		addMetadata(list, metadata.keySet());
		Collections.sort(list);
		return list;
	}
}
