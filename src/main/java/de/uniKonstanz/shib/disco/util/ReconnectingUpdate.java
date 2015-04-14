package de.uniKonstanz.shib.disco.util;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Database helper for automatically reconnecting update statements (ie. doesn't
 * return data). Implements a single retry, which catches the most common case
 * of database timeouts or restarts, but doesn't cause excessive pointless
 * retries when the database is down.
 * 
 * @param <Params>
 *            type of parameter passed to {@link #executeUpdate(Object)}
 */
public abstract class ReconnectingUpdate<Params> extends
		ReconnectingStatement<Params> {
	/**
	 * @param db
	 *            {@link ReconnectingDatabase}; can be common to multiple
	 *            statements but then they must not be allowed to run
	 *            concurrently
	 * @param query
	 *            the SQL query to execute (must be {@code INSERT},
	 *            {@code UPDATE} or {@DELETE}, ie. something that
	 *            doesn't return data)
	 * @param transaction
	 *            <code>true</code> to wrap statement execution in a transaction
	 * @throws SQLException
	 *             if the query fails to parse
	 */
	public ReconnectingUpdate(final ReconnectingDatabase db,
			final String query, final boolean transaction) throws SQLException {
		super(db, query, transaction);
	}

	/**
	 * Does not wrap statement execution in a transaction, except for the
	 * implicit transaction(s) used by JDBC autocommit mode.
	 * 
	 * @param db
	 *            {@link ReconnectingDatabase}; can be common to multiple
	 *            statements but then they must not be allowed to run
	 *            concurrently
	 * @param query
	 *            the SQL query to execute (must be {@code INSERT},
	 *            {@code UPDATE} or {@DELETE}, ie. something that
	 *            doesn't return data)
	 * @throws SQLException
	 *             if the query fails to parse
	 */
	public ReconnectingUpdate(final ReconnectingDatabase db, final String query)
			throws SQLException {
		super(db, query, false);
	}

	/**
	 * Main update method called externally.
	 * 
	 * @param p
	 *            input parameter passed to {@link #exec(Object)}, if required
	 * @throws SQLException
	 *             if the retry fails
	 */
	public void executeUpdate(final Params p) throws SQLException {
		prepareStatement();
		try {
			exec(p);
		} catch (final SQLException e) {
			handleException(e);
		}

		// retry once. this fixes database timeouts or restarts, but doesn't
		// cause excessive retries when the database is down.
		prepareStatement();
		try {
			exec(p);
		} catch (final SQLException e) {
			handleException(e);
			throw e;
		}
	}

	/**
	 * Wraps {@link PreparedStatement#executeUpdate()}; to be called by
	 * {@link #exec(Object)}.
	 */
	@Override
	protected void executeUpdate() throws SQLException {
		super.executeUpdate();
	}

	/**
	 * Method that performs the actual database transaction. Must not modify
	 * anything except the database because it will be retried on error. On the
	 * other hand, it doesn't have to do any handling of SQL errors; these just
	 * cause a retry.
	 * 
	 * @param p
	 *            parameters as passed into {@link #executeUpdate()(Object)}
	 * @throws SQLException
	 *             to cause a retry
	 */
	protected abstract void exec(Params p) throws SQLException;
}
