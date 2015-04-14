package de.uniKonstanz.shib.disco.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
abstract class ReconnectingStatement<T> {
	private static final Logger LOGGER = Logger
			.getLogger(ReconnectingStatement.class.getCanonicalName());
	private final ReconnectingDatabase db;
	private final String query;
	private PreparedStatement stmt;
	private final boolean transaction;

	/**
	 * @param db
	 *            the {@link ReconnectingDatabase}
	 * @param query
	 *            sql statement for {@link PreparedStatement}
	 * @param transaction
	 *            <code>true</code> to wrap execution in a transaction
	 * @throws SQLException
	 *             if the statement is invalid
	 */
	public ReconnectingStatement(final ReconnectingDatabase db,
			final String query, final boolean transaction) throws SQLException {
		this.db = db;
		this.query = query;
		this.transaction = transaction;
		// prepare statement eagerly to detect problems, such as syntax errors
		// or missing tables
		prepareStatement();
	}

	/**
	 * Prepares the statement. Must be called by subclasses before performing
	 * anything that requires a valid {@link PreparedStatement} to be present.
	 * 
	 * If a database error occurs, retries once. This catches the common case
	 * where the database connection was broken or the database was restarted.
	 * If the connection is more seriously broken, the database is probably
	 * down, and further retries won't change that.
	 * 
	 * @return the {@link PreparedStatement}, either a new one or the recycled
	 *         previous one
	 * 
	 * @throws SQLException
	 *             if the second retry fails
	 */
	void prepareStatement() throws SQLException {
		if (stmt != null)
			return;

		// try to prepare the statement. if the connection is still up, this
		// will succeed. but most likely the statement failed because the
		// connection was broken; prepared statements don't usually just fail.
		LOGGER.info("re-preparing statement " + query);
		try {
			tryPrepareStatement();
		} catch (final SQLException e) {
			LOGGER.log(Level.WARNING,
					"failed to prepare statement; will retry", e);
		}

		// first attempt failed. probably the database connection was broken or
		// timed out.
		// let's try again; the database will try to reconnect. if it fails to
		// do so, the exception is passed up.
		tryPrepareStatement();
	}

	/** Non-retrying {@link PreparedStatement} helper. */
	private void tryPrepareStatement() throws SQLException {
		try {
			final Connection conn = db.getConnection();
			if (transaction)
				conn.setAutoCommit(false);
			stmt = conn.prepareStatement(query);
		} catch (final SQLException e) {
			stmt = null;
			db.close();
			throw e;
		}
	}

	/**
	 * Cleans up the statement if it was damaged by an {@link SQLException}, and
	 * prepares it for a new call to {@link #prepareStatement()}. Must be called
	 * by subclasses whenever an SQL exception was thrown, before retrying.
	 * 
	 * @param e
	 */
	void handleException(final SQLException e) {
		LOGGER.log(Level.WARNING, "failed to execute statement");
		try {
			stmt.close();
		} catch (final SQLException e1) {
			// failed to close the statement. let's hope it will be
			// correctly reclaimed by garbage collection.
			LOGGER.log(Level.WARNING, "failed to close statement", e);
		}
		stmt = null;
	}

	/** Wraps {@link PreparedStatement#setInt(int, int)}. */
	protected void setInt(final int index, final int value) throws SQLException {
		stmt.setInt(index, value);
	}

	/** Wraps {@link PreparedStatement#setString(int, String)}. */
	protected void setString(final int index, final String value)
			throws SQLException {
		stmt.setString(index, value);
	}

	/** Wraps {@link PreparedStatement#executeUpdate()}. */
	void executeUpdate() throws SQLException {
		stmt.executeUpdate();
	}

	/** Wraps {@link PreparedStatement#executeQuery()}. */
	ResultSet executeQuery() throws SQLException {
		return stmt.executeQuery();
	}

	/** Wraps {@link PreparedStatement#addBatch()}. */
	void addBatch() throws SQLException {
		stmt.addBatch();
	}

	/** Wraps {@link PreparedStatement#executeBatch()}. */
	void executeBatch() throws SQLException {
		stmt.executeBatch();
	}
}
