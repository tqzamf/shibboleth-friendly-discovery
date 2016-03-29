package de.uniKonstanz.shib.disco.util;

import java.sql.Connection;
import java.sql.SQLException;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

/**
 * Simple {@link DataSource} wrapper that hides all the {@link InitialContext}
 * magic.
 */
public class ConnectionPool {
	private final DataSource dataSource;

	/**
	 * @throws SQLException
	 *             if the connection fails
	 * @throws NamingException
	 *             if the {@link InitialContext} magic fails
	 */
	public ConnectionPool() throws SQLException, NamingException {
		final InitialContext context = new InitialContext();
		final Context env = (Context) context.lookup("java:/comp/env");
		dataSource = (DataSource) env.lookup("jdbc.database");
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
		return dataSource.getConnection();
	}
}
