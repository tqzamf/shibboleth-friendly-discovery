package de.uniKonstanz.shib.disco.util;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * {@link ReconnectingUpdate} with support for
 * {@link PreparedStatement#addBatch()}.
 * 
 * @param <Params>
 *            type of parameter passed to {@link #executeUpdate(Object)}
 */
public abstract class ReconnectingBatchUpdate<Params> extends
		ReconnectingUpdate<Params> {
	/**
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
	public ReconnectingBatchUpdate(final ReconnectingDatabase db,
			final String query) throws SQLException {
		super(db, query, true);
	}

	/**
	 * Wraps {@link PreparedStatement#executeBatch()} [sic]; to be called by
	 * {@link #exec(Object)}.
	 */
	@Override
	protected void executeUpdate() throws SQLException {
		super.executeBatch();
		super.commit();
	}

	/** Wraps {@link PreparedStatement#addBatch()}. */
	@Override
	protected void addBatch() throws SQLException {
		super.addBatch();
	}
}
