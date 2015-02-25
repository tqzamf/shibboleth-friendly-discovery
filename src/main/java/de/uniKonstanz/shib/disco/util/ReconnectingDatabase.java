package de.uniKonstanz.shib.disco.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ReconnectingDatabase {
	private static final Logger LOGGER = Logger
			.getLogger(ReconnectingDatabase.class.getCanonicalName());
	private final String jdbcURL;
	private Connection conn;

	public ReconnectingDatabase(final String jdbcURL) throws SQLException {
		this.jdbcURL = jdbcURL;
		// connect eagerly to detect problems, such as incorrect URL
		connect();
	}

	private void connect() throws SQLException {
		LOGGER.info("connecting to database " + jdbcURL);
		conn = DriverManager.getConnection(jdbcURL);
		conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
	}

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

	Connection getConnection() throws SQLException {
		if (conn == null)
			connect();
		return conn;
	}
}
