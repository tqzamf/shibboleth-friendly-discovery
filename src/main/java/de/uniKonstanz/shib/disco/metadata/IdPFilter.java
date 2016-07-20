package de.uniKonstanz.shib.disco.metadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.codehaus.jackson.type.TypeReference;

import de.uniKonstanz.shib.disco.util.HTTP;

public class IdPFilter implements Runnable {
	private static final Logger LOGGER = Logger.getLogger(IdPFilter.class
			.getCanonicalName());

	/**
	 * Maximum time, in milliseconds, that the filter update will block a
	 * request before returning stale data instead. This is a trade-off -- the
	 * users wants an up-to-date list of IdPs, but doesn't want to wait.
	 */
	private static final long MAX_DELAY = 500;
	/**
	 * Maximum time, in milliseconds, that the filter update will block a
	 * request before giving up and not filtering at all. This is a very
	 * different trade-off because showing the full, unfiltered list is much
	 * more annoying than just a stale list which, in almost all cases, will
	 * differ by a single recently changed IdP at most.
	 */
	private static final long MAX_DELAY_WITHOUT_FALLBACK = 3000;
	/**
	 * Amount of time, in seconds, that the list of accepted IdPs will be
	 * cached.
	 */
	private static final int MAX_AGE = MetadataUpdateThread.INTERVAL;

	private final String url;
	private final MetadataUpdateThread meta;
	private HashSet<IdPMeta> idps;
	private Thread thread;
	private long lastReload;

	public IdPFilter(final MetadataUpdateThread meta, final String url) {
		this.meta = meta;
		this.url = url;
	}

	@Override
	public void run() {
		HashSet<IdPMeta> list = null;
		try {
			list = update();
		} finally {
			synchronized (this) {
				idps = list;
				thread = null;
				lastReload = System.currentTimeMillis();
			}
		}
	}

	private HashSet<IdPMeta> update() {
		final List<IdP> idps;
		try {
			idps = HTTP.getJSON(url, new TypeReference<List<IdP>>() {
			});
		} catch (final IOException e) {
			LOGGER.log(Level.INFO, "failed to update filter " + url, e);
			return null;
		}
		final ArrayList<String> ids = new ArrayList<String>(idps.size());
		for (final IdP idp : idps)
			ids.add(idp.entityID);

		final HashSet<IdPMeta> list = new HashSet<IdPMeta>(idps.size());
		meta.addMetadata(list, ids);
		return list;
	}

	public Collection<IdPMeta> getIdPs() {
		// deliberately unsynchronized. in the worst case, this will return
		// stale data, but that also happens when the reload takes too long.
		if (!isStale())
			return idps;

		final Thread thread;
		final long delay;
		synchronized (this) {
			// avoid the race where the list is updated after the first check.
			// don't immediately perform another update in that situation.
			if (!isStale())
				return idps;

			if (this.thread == null) {
				// start a new thread
				thread = new Thread(this, "filter update " + url);
				thread.start();
				this.thread = thread;
			} else
				// there is already an update running; let's just wait for that
				// to finish
				thread = this.thread;

			if (idps != null)
				delay = MAX_DELAY;
			else
				delay = MAX_DELAY_WITHOUT_FALLBACK;
		}

		// give the update some time to download a new list of IdPs
		try {
			thread.join(delay);
		} catch (final InterruptedException e) {
			// someone wants us to die. restore the interrupt flag.
			Thread.currentThread().interrupt();
		}

		// the update may have been successful or not. if it was, this will
		// return the new data; otherwise it may be stale.
		return idps;
	}

	private boolean isStale() {
		return System.currentTimeMillis() - lastReload > 1000 * MAX_AGE;
	}
}
