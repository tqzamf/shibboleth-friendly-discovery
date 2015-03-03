package de.uniKonstanz.shib.disco.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link Connection} wrapper with some logic to simplify retries on
 * {@link SQLException}.
 * 
 * Not thread safe because of the {@link #close()} method. Use appropriate
 * locking so that only one thread accesses this object at a time.
 */
public class ReconnectingDatabase {
	private static final Logger LOGGER = Logger
			.getLogger(ReconnectingDatabase.class.getCanonicalName());
	private final String jdbcURL;
	private Connection conn;

	/**
	 * @param jdbcURL
	 *            JDBC connection URL, as passed to
	 *            {@link DriverManager#getConnection(String)}; including
	 *            necessary credentials
	 * @throws SQLException
	 *             if the connection fails
	 */
	public ReconnectingDatabase(final String jdbcURL) throws SQLException {
		this.jdbcURL = jdbcURL;
		// connect eagerly to detect problems, such as incorrect URL
		connect();
	}

	/** Opens the database connection. */
	private void connect() throws SQLException {
		LOGGER.info("connecting to database " + jdbcURL);
		conn = DriverManager.getConnection(jdbcURL);
		conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
	}

	/**
	 * Closes the database connection. To be called after a database operation
	 * fails; makes sure the next call to {@link #getConnection()} will
	 * reconnect.
	 */
	public void close() {
		if (conn != null) {
			LOGGER.info("closing connection to database");
			try {
				conn.close();
			} catch (final SQLException e) {
				// cannot really do much about SQL exceptions, other than close
				// and reestablish the connection... but that's exactly what
				// went wrong here!
				LOGGER.log(Level.WARNING,
						"failed to close database connection", e);
			}
		}
		conn = null;
	}

	/**
	 * Gets the current database connection if there is any, reconnecting if not
	 * currently conencted.
	 * 
	 * @return a database {@link Connection}
	 * @throws SQLException
	 *             if reconnection was necessary but unsuccessful
	 */
	public Connection getConnection() throws SQLException {
		if (conn == null)
			connect();
		return conn;
	}
}
