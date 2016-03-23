package de.uniKonstanz.shib.disco.loginlogger;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import de.uniKonstanz.shib.disco.AbstractShibbolethServlet;
import de.uniKonstanz.shib.disco.util.AutoRetryStatement;
import de.uniKonstanz.shib.disco.util.ConnectionPool;

/**
 * Background threads for {@link LoginServlet} that asynchronously removes
 * logins older than 30 days from the database.
 */
public class DatabaseCleanupThread extends Thread {
	private static final Logger LOGGER = Logger
			.getLogger(DatabaseCleanupThread.class.getCanonicalName());
	/**
	 * Clean up every 24 hours. This may cause database timeouts, but the
	 * {@link ConnectionPool} will perform correct retries, and proper databases don't
	 * time out anyway.
	 */
	private static final long INTERVAL = 24 * 60 * 60 * 1000;
	private final AutoRetryStatement<Void, Integer> cleanup;

	/**
	 * @param db
	 *            the {@link ConnectionPool} to push values to
	 * @throws ServletException
	 *             if the database statement cannot be prepared
	 */
	public DatabaseCleanupThread(final ConnectionPool db) throws ServletException {
		super("database cleanup thread");
		cleanup = new AutoRetryStatement<Void, Integer>(db,
				"delete from loginstats" + " where created < ? or count <= 0",
				false) {
			@Override
			protected Void exec(final PreparedStatement stmt, final Integer day)
					throws SQLException {
				stmt.setInt(1, day);
				stmt.executeUpdate();
				return null;
			}
		};
	}

	/**
	 * Terminates the background thread, waiting until it has actually shut
	 * down.
	 */
	public void shutdown() {
		interrupt();
		if (!isAlive())
			return;
		try {
			join();
		} catch (final InterruptedException e) {
			LOGGER.log(Level.SEVERE, "cleanup thread shutdown failed", e);
		}
	}

	@Override
	public void run() {
		while (!interrupted()) {
			final int day = AbstractShibbolethServlet.getCurrentDay() - 30;
			LOGGER.info("performing database cleanup for days before " + day);
			cleanup(day);

			try {
				Thread.sleep(INTERVAL);
			} catch (final InterruptedException e1) {
				break;
			}
		}
	}

	/**
	 * Cleans up old login entries, retrying the database operations if
	 * necessary.
	 */
	private void cleanup(final int day) {
		try {
			cleanup.execute(day);
		} catch (final SQLException e) {
			// retry faile, ie. reconnecting failed. this means the database is
			// probably down; there is no point trying to reconnect any further.
			// perhaps the next database connection will succeed again.
			// nothing severe has happened: in the worst case, stale entries
			// will continue to linger for a few more days.
			LOGGER.log(Level.WARNING,
					"failed to cleanup counts; database down?", e);
		}
	}
}
