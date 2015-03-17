package de.uniKonstanz.shib.disco.util;

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
public class ReconnectingStatement {
	private static final Logger LOGGER = Logger
			.getLogger(ReconnectingStatement.class.getCanonicalName());
	private final ReconnectingDatabase db;
	private final String query;
	private PreparedStatement stmt;

	/**
	 * @param db
	 *            the {@link ReconnectingDatabase}
	 * @param query
	 *            sql statement for {@link PreparedStatement}
	 * @throws SQLException
	 *             if the statement is invalid
	 */
	public ReconnectingStatement(final ReconnectingDatabase db,
			final String query) throws SQLException {
		this.db = db;
		this.query = query;
		// prepare statement eagerly to detect problems, such as syntax errors
		// or missing tables
		prepareStatement();
	}

	/**
	 * Prepares the statement. If a database error occurs, retries once. This
	 * catches the common case where the database connection was broken or the
	 * database was restarted.
	 * 
	 * @throws SQLException
	 *             if the second retry fails
	 */
	public void prepareStatement() throws SQLException {
		if (stmt != null)
			return;

		// try to prepare the statement. if the connection is still up, this
		// will succeed. but most likely the statement failed because the
		// connection was broken; prepared statements don't usually just fail.
		LOGGER.info("re-preparing statement " + query);
		try {
			stmt = tryPrepareStatement();
			return;
		} catch (final SQLException e) {
			LOGGER.log(Level.WARNING,
					"failed to prepare statement; will retry", e);
		}

		// first attempt failed. probably the database connection was broken or
		// timed out.
		// let's try again; the database will to reconnect. if it fails to do
		// so, the exception is passed up.
		stmt = tryPrepareStatement();
	}

	/** Non-retrying {@link PreparedStatement} helper. */
	private PreparedStatement tryPrepareStatement() throws SQLException {
		try {
			return db.getConnection().prepareStatement(query);
		} catch (final SQLException e) {
			db.close();
			throw e;
		}
	}

	/** Wraps {@link PreparedStatement#setInt(int, int)}. */
	public void setInt(final int index, final int value) throws SQLException {
		checkPrepared();
		stmt.setInt(index, value);
	}

	/** Wraps {@link PreparedStatement#setString(int, String)}. */
	public void setString(final int index, final String value)
			throws SQLException {
		checkPrepared();
		stmt.setString(index, value);
	}

	/**
	 * Wraps {@link PreparedStatement#executeUpdate()}, but also closes the
	 * database connection on failure.
	 */
	public void executeUpdate() throws SQLException {
		checkPrepared();
		try {
			stmt.executeUpdate();
		} catch (final SQLException e) {
			LOGGER.log(Level.WARNING, "failed to execute statement");
			try {
				stmt.close();
			} catch (final SQLException e1) {
				// failed to close the statement. let's hope it will be
				// correctly reclaimed by garbage collection.
				LOGGER.log(Level.WARNING, "failed to close statement", e);
			}
			stmt = null;
			throw e;
		}
	}

	/**
	 * Wraps {@link PreparedStatement#executeQuery()}, but also closes the
	 * database connection on failure.
	 */
	public ResultSet executeQuery() throws SQLException {
		checkPrepared();
		try {
			return stmt.executeQuery();
		} catch (final SQLException e) {
			LOGGER.log(Level.WARNING, "failed to execute statement");
			try {
				stmt.close();
			} catch (final SQLException e1) {
				// failed to close the statement. let's hope it will be
				// correctly reclaimed by garbage collection.
				LOGGER.log(Level.WARNING, "failed to close statement", e);
			}
			stmt = null;
			throw e;
		}
	}

	/**
	 * Throws {@link IllegalStateException} if the statement wasn't prepared
	 * before a call to {@link #executeQuery()} or {@link #executeUpdate()}.
	 */
	private void checkPrepared() {
		if (stmt == null)
			throw new IllegalStateException("statement not prepared!");
	}
}
