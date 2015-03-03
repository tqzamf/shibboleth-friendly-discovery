package de.uniKonstanz.shib.disco.loginlogger;

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
import de.uniKonstanz.shib.disco.util.ReconnectingDatabase;
import de.uniKonstanz.shib.disco.util.ReconnectingStatement;

/**
 * Handles loading ranked lists of IdPs from the database. Results are cached
 * for 1 hour to keep the number of database queries down.
 */
public class IdPRanking {
	private static final Logger LOGGER = Logger.getLogger(IdPRanking.class
			.getCanonicalName());
	private final ReconnectingStatement stmt;
	private final LoadingCache<Integer, List<String>> cache;
	private final int numIdPs;
	private ReconnectingStatement globalStmt;

	/**
	 * @param db
	 *            the {@link ReconnectingDatabase} to load data from
	 * @param numIdPs
	 *            number of IdPs to return for each query
	 * @throws ServletException
	 *             if the database statement cannot be prepared
	 */
	public IdPRanking(final ReconnectingDatabase db, final int numIdPs)
			throws ServletException {
		this.numIdPs = numIdPs;
		try {
			stmt = new ReconnectingStatement(db, "select entityid"
					+ " from loginstats where iphash = ?"
					+ " group by entityid order by sum(count) desc");
			globalStmt = new ReconnectingStatement(db, "select entityid"
					+ " from loginstats"
					+ " group by entityid order by sum(count) desc");
		} catch (final SQLException e) {
			LOGGER.log(Level.SEVERE, "cannot prepare database statement", e);
			throw new ServletException("cannot connect to database");
		}
		cache = CacheBuilder.newBuilder().expireAfterWrite(1, TimeUnit.HOURS)
				.maximumSize(10 * AbstractShibbolethServlet.MAX_IDPS)
				.build(new CacheLoader<Integer, List<String>>() {
					@Override
					public List<String> load(final Integer key)
							throws SQLException {
						return loadIdPList(key);
					}
				});
	}

	/**
	 * Gets the {@link #numIdPs} globally most popular IdPs.
	 * 
	 * @return list of up to {@link #numIdPs} entityIDs
	 */
	public List<String> getGlobalIdPList() {
		return getIdPList(AbstractShibbolethServlet.NETHASH_UNDEFINED);
	}

	/**
	 * Gets the {@link #numIdPs} most popular IdPs for the given network hash.
	 * 
	 * @return list of up to {@link #numIdPs} entityIDs
	 */
	public List<String> getIdPList(final int nethash) {
		try {
			return cache.get(nethash);
		} catch (final ExecutionException e) {
			if (!(e.getCause() instanceof SQLException))
				// SQL exceptions have already been reported; no need to report
				// them again. everything else is unexpected.
				LOGGER.log(Level.SEVERE, "exception getting popular IdPs for "
						+ nethash, e);
			return null;
		}
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
			return tryGetIdPList(nethash);
		} catch (final SQLException e) {
			LOGGER.log(Level.WARNING, "failed to get popular IdPs for "
					+ nethash + "; will retry", e);
		}

		// first attempt failed; database connection was somehow broken. let's
		// try again; the statement will reconnect itself if possible.
		try {
			return tryGetIdPList(nethash);
		} catch (final SQLException e) {
			// second attempt failed as well, ie. reconnecting failed. this
			// means the database is probably down; there is no point trying to
			// reconnect any further. perhaps the next database connection will
			// succeed again.
			LOGGER.log(Level.SEVERE, "failed to get popular IdPs for "
					+ nethash + "; database down?", e);
			throw e;
		}
	}

	/** Non-retrying database helper method. */
	private List<String> tryGetIdPList(final int nethash) throws SQLException {
		if (nethash == AbstractShibbolethServlet.NETHASH_UNDEFINED)
			synchronized (globalStmt) {
				globalStmt.prepareStatement();
				return toList(globalStmt);
			}
		else
			synchronized (stmt) {
				stmt.prepareStatement();
				stmt.setInt(1, nethash);
				return toList(stmt);
			}
	}

	/**
	 * Executes a statement and returns up to {@link #numIdPs} {@link String}
	 * results as a list.
	 */
	private List<String> toList(final ReconnectingStatement statement)
			throws SQLException {
		final ResultSet res = statement.executeQuery();
		final ArrayList<String> list = new ArrayList<String>(numIdPs);
		while (res.next() && list.size() < numIdPs)
			list.add(res.getString(1));
		res.close();
		return list;
	}
}
