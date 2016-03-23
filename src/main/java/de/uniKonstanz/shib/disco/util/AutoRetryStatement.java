package de.uniKonstanz.shib.disco.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link PreparedStatement} wrapper with some logic to simplify retries on
 * {@link SQLException}.
 * 
 * Not thread safe, simply because the
 * {@link PreparedStatement#setObject(int, Object)} API has inherent race
 * conditions. Use appropriate locking so that only one thread accesses this
 * object at a time.
 */
public abstract class AutoRetryStatement<Result, Params> {
	private static final Logger LOGGER = Logger
			.getLogger(AutoRetryStatement.class.getCanonicalName());
	private final ConnectionPool db;
	private final String query;
	private final boolean transaction;

	/**
	 * @param db
	 *            the {@link ConnectionPool}
	 * @param query
	 *            sql statement for {@link PreparedStatement}
	 * @param transaction
	 *            <code>true</code> to wrap execution in a transaction
	 */
	public AutoRetryStatement(final ConnectionPool db, final String query,
			final boolean transaction) {
		this.db = db;
		this.query = query;
		this.transaction = transaction;
	}

	/**
	 * Main method to be called externally.
	 * 
	 * @param p
	 *            input parameter passed to
	 *            {@link #exec(PreparedStatement, Object)}, if required
	 * @return data returned by {@link #exec(PreparedStatement, Object)}
	 * @throws SQLException
	 *             if the retry fails
	 */
	public Result execute(final Params p) throws SQLException {
		try {
			return tryExecute(p);
		} catch (final SQLException e) {
			LOGGER.log(Level.WARNING, "failed to execute statement", e);
		}

		// retry once. this fixes database timeouts or restarts, but doesn't
		// cause excessive retries when the database is down.
		return tryExecute(p);
	}

	private Result tryExecute(final Params p) throws SQLException {
		try (final Connection conn = db.getConnection()) {
			if (transaction)
				conn.setAutoCommit(false);

			try (final PreparedStatement stmt = conn.prepareStatement(query)) {
				final Result res = exec(stmt, p);
				if (transaction)
					conn.commit();
				return res;
			} catch (final SQLException e) {
				try {
					if (transaction)
						// if using transactions, try to roll back any ongoing
						// transactions because they will have to be restared
						// from scratch. if the connection is dead, this will
						// fail, but then it won't be necessary anyway.
						conn.rollback();
				} catch (final SQLException ex) {
					e.addSuppressed(ex);
				}
				throw e;
			}
		}
	}

	/**
	 * Method that performs the actual database transaction. Must not modify
	 * anything except the database because it will be retried on error. On the
	 * other hand, it doesn't have to do any handling of SQL errors; these just
	 * cause a retry.
	 * 
	 * @param stmt
	 *            {@link PreparedStatement} to operate on
	 * @param p
	 *            parameters as passed into {@link #execute(Object)}
	 * 
	 * @return data to return from {@link #execute(Object)}
	 * @throws SQLException
	 *             to cause a retry
	 */
	protected abstract Result exec(PreparedStatement stmt, Params p)
			throws SQLException;
}
