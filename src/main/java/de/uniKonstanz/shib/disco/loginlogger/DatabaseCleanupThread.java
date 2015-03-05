package de.uniKonstanz.shib.disco.loginlogger;

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import de.uniKonstanz.shib.disco.AbstractShibbolethServlet;
import de.uniKonstanz.shib.disco.util.ReconnectingDatabase;
import de.uniKonstanz.shib.disco.util.ReconnectingStatement;

/**
 * Background threads for {@link LoginServlet} that asynchronously removes
 * logins older than 30 days from the database.
 */
public class DatabaseCleanupThread extends Thread {
	private static final Logger LOGGER = Logger
			.getLogger(DatabaseCleanupThread.class.getCanonicalName());
	/**
	 * Clean up every 6 hours. This may cause database timeouts, but the
	 * {@link ReconnectingDatabase} will perform correct retries, and proper
	 * databases don't time out anyway.
	 */
	private static final long INTERVAL = 6 * 60 * 60 * 1000;
	private final ReconnectingDatabase db;
	private final ReconnectingStatement stmt;

	/**
	 * @param db
	 *            the {@link ReconnectingDatabase} to push values to
	 * @throws ServletException
	 *             if the database statement cannot be prepared
	 */
	public DatabaseCleanupThread(final ReconnectingDatabase db)
			throws ServletException {
		super("login database worker");
		this.db = db;
		try {
			stmt = new ReconnectingStatement(db, "delete from loginstats"
					+ " where created < ?");
		} catch (final SQLException e) {
			LOGGER.log(Level.SEVERE, "cannot prepare database statement", e);
			throw new ServletException("cannot connect to database");
		}
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

		db.close();
	}

	/**
	 * Cleans up old login entries, retrying the database operations if
	 * necessary.
	 */
	private void cleanup(final int day) {
		try {
			tryCleanup(day);
			return;
		} catch (final SQLException e) {
			LOGGER.log(Level.WARNING, "failed to cleanup counts; will retry", e);
		}

		// first attempt failed; database connection was somehow broken. let's
		// try again; the statement will try to reconnect itself if possible.
		try {
			tryCleanup(day);
		} catch (final SQLException e) {
			// second attempt failed as well, ie. reconnecting failed. this
			// means the database is probably down; there is no point trying to
			// reconnect any further. perhaps the next database connection will
			// succeed again.
			// nothing severe has happened: in the worst case, stale entries
			// will continue to linger for a few more days.
			LOGGER.log(Level.WARNING,
					"failed to cleanup counts; database down?", e);
		}
	}

	/** No-retrying database helper method. */
	private void tryCleanup(final int day) throws SQLException {
		stmt.prepareStatement();
		stmt.setInt(1, day);
		stmt.executeUpdate();
	}
}