package de.uniKonstanz.shib.disco.util;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Database helper for automatically reconnecting query statements (ie.
 * returning data). Implements a single retry, which catches the most common
 * case of database timeouts or restarts, but doesn't cause excessive pointless
 * retries when the database is down.
 * 
 * @param <Result>
 *            type of results returned by {@link #executeQuery(Object)}
 * @param <Params>
 *            type of parameter passed to {@link #executeQuery(Object)}
 */
public abstract class ReconnectingQuery<Result, Params> extends
		ReconnectingStatement<Params> {
	/**
	 * @param db
	 *            {@link ReconnectingDatabase}; can be common to multiple
	 *            statements but then they must not be allowed to run
	 *            concurrently
	 * @param query
	 *            the SQL query to execute (must be {@code SELECT} or something
	 *            else that returns data)
	 * @throws SQLException
	 *             if the query fails to parse
	 */
	public ReconnectingQuery(final ReconnectingDatabase db, final String query)
			throws SQLException {
		super(db, query, false);
	}

	/**
	 * Main query method called externally.
	 * 
	 * @param p
	 *            input parameter passed to {@link #exec(Object)}, if required
	 * @return data returned by {@link #exec(Object)}
	 * @throws SQLException
	 *             if the retry fails
	 */
	public Result executeQuery(final Params p) throws SQLException {
		prepareStatement();
		try {
			return exec(p);
		} catch (final SQLException e) {
			handleException(e);
		}

		// retry once. this fixes database timeouts or restarts, but doesn't
		// cause excessive retries when the database is down.
		prepareStatement();
		try {
			return exec(p);
		} catch (final SQLException e) {
			handleException(e);
			throw e;
		}
	}

	/**
	 * Wraps {@link PreparedStatement#executeQuery()}; to be called by
	 * {@link #exec(Object)}.
	 */
	@Override
	protected ResultSet executeQuery() throws SQLException {
		return super.executeQuery();
	}

	/**
	 * Method that performs the actual database transaction. Must not modify
	 * anything except the database because it will be retried on error. On the
	 * other hand, it doesn't have to do any handling of SQL errors; these just
	 * cause a retry.
	 * 
	 * @param p
	 *            parameters as passed into {@link #executeQuery(Object)}
	 * @return data to return from {@link #executeQuery(Object)}
	 * @throws SQLException
	 *             to cause a retry
	 */
	protected abstract Result exec(Params p) throws SQLException;
}
