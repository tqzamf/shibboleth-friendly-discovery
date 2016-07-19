package de.uniKonstanz.shib.disco.loginlogger;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import de.uniKonstanz.shib.disco.AbstractShibbolethServlet;
import de.uniKonstanz.shib.disco.metadata.IdPMeta;
import de.uniKonstanz.shib.disco.metadata.MetadataUpdateThread;
import de.uniKonstanz.shib.disco.util.AutoRetryStatement;
import de.uniKonstanz.shib.disco.util.ConnectionPool;

/**
 * Handles loading ranked lists of IdPs from the database. Results are cached
 * for 1 hour to keep the number of database queries down.
 */
public class IdPRanking {
	private static final Logger LOGGER = Logger.getLogger(IdPRanking.class
			.getCanonicalName());
	private final AutoRetryStatement<List<String>, Integer> getIdPList;
	private final AutoRetryStatement<List<String>, Void> getGlobalIdPList;
	private final LoadingCache<Integer, IdPMeta[]> cache;

	/**
	 * @param db
	 *            the {@link ConnectionPool} to load data from
	 * @param meta
	 *            the {@link MetadataUpdateThread} containing the metadata
	 *            objects for all IdPs
	 * @throws ServletException
	 *             if the database statement cannot be prepared
	 */
	public IdPRanking(final ConnectionPool db, final MetadataUpdateThread meta)
			throws ServletException {

		getIdPList = new AutoRetryStatement<List<String>, Integer>(db,
				"select entityid from loginstats where iphash = ?"
						+ " group by entityid order by sum(count) desc", false) {
			@Override
			protected List<String> exec(final PreparedStatement stmt,
					final Integer nethash) throws SQLException {
				stmt.setInt(1, nethash);
				return toList(stmt.executeQuery());
			}
		};
		getGlobalIdPList = new AutoRetryStatement<List<String>, Void>(db,
				"select entityid from loginstats"
						+ " group by entityid order by sum(count) desc", false) {
			@Override
			protected List<String> exec(final PreparedStatement stmt,
					final Void p) throws SQLException {
				return toList(stmt.executeQuery());
			}
		};

		// no size limit. each entry is ~64 bytes (6 references, length,
		// overhead) plus some overhead for the set, and there are at most 65k
		// possible keys. thus the cache cannot get significantly larger than
		// 4-40 MB anyway, which is less than Tomcat itself.
		// no soft references either; throwing away the tiny values doesn't free
		// enough memory to be worth the effort.
		cache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.HOURS)
				.build(new CacheLoader<Integer, IdPMeta[]>() {
					@Override
					public IdPMeta[] load(final Integer key)
							throws SQLException {
						final List<String> idps = loadIdPList(key);
						final ArrayList<IdPMeta> list = new ArrayList<IdPMeta>(
								idps.size());
						meta.addMetadata(list, idps);
						return list.toArray(new IdPMeta[list.size()]);
					}
				});
	}

	/**
	 * Executes a statement and returns up to {@link #numIdPs} {@link String}
	 * results as a list.
	 */
	private List<String> toList(final ResultSet res) throws SQLException {
		final ArrayList<String> list = new ArrayList<String>();
		while (res.next())
			list.add(res.getString(1));
		res.close();
		return list;
	}

	/**
	 * Obtains the list of IdPs for the given network hash from the database,
	 * retrying if necessary.
	 * 
	 * @param nethash
	 *            client network hash
	 * @return up to 6 entityIDs
	 * @throws SQLException
	 *             on database errors
	 */
	private List<String> loadIdPList(final int nethash) throws SQLException {
		try {
			if (nethash == AbstractShibbolethServlet.NETHASH_UNDEFINED)
				return getGlobalIdPList.execute(null);
			return getIdPList.execute(nethash);
		} catch (final SQLException e) {
			// database retry failed, ie. reconnecting failed. this means the
			// database is probably down; there is no point trying to reconnect
			// any further. perhaps the next database connection will succeed
			// again; users do tend to hit F5...
			LOGGER.log(Level.SEVERE, "failed to get popular IdPs for "
					+ nethash + "; database down?", e);
			throw e;
		}
	}

	/**
	 * Gets the {@link #numIdPs} globally most popular IdPs.
	 * 
	 * @return list of up to {@link #numIdPs} entityIDs
	 */
	public IdPMeta[] getGlobalIdPList() {
		return getIdPList(AbstractShibbolethServlet.NETHASH_UNDEFINED);
	}

	/**
	 * Gets the {@link #numIdPs} most popular IdPs for the given network hash.
	 * 
	 * @return list of up to {@link #numIdPs} entityIDs
	 */
	public IdPMeta[] getIdPList(final int nethash) {
		try {
			return cache.get(nethash);
		} catch (final ExecutionException e) {
			if (!(e.getCause() instanceof SQLException))
				// SQL exceptions have already been reported (in loadIdPList);
				// no need to report them again. everything else is unexpected.
				LOGGER.log(Level.SEVERE, "exception getting popular IdPs for "
						+ nethash, e);
			return null;
		}
	}
}
