package de.uniKonstanz.shib.disco.loginlogger;

import java.sql.SQLException;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import de.uniKonstanz.shib.disco.util.ReconnectingDatabase;
import de.uniKonstanz.shib.disco.util.ReconnectingStatement;

class DatabaseWorkerThread extends Thread {
	private static final Logger LOGGER = Logger
			.getLogger(DatabaseWorkerThread.class.getCanonicalName());
	private final BlockingQueue<Entry<LoginTuple, Counter>> updateQueue = new LinkedBlockingQueue<Entry<LoginTuple, Counter>>();
	private static final Entry<LoginTuple, Counter> END = new Entry<LoginTuple, Counter>() {
		public Counter setValue(final Counter value) {
			throw new UnsupportedOperationException();
		}

		public Counter getValue() {
			throw new UnsupportedOperationException();
		}

		public LoginTuple getKey() {
			throw new UnsupportedOperationException();
		}
	};
	private final ReconnectingDatabase db;
	private final ReconnectingStatement stmt;

	public DatabaseWorkerThread(final ReconnectingDatabase db)
			throws ServletException {
		super("login database worker");
		this.db = db;
		try {
			stmt = new ReconnectingStatement(db, "insert into"
					+ " loginstats(iphash, entityid, count, created)"
					+ " values(?, ?, ?, current_timestamp)");
		} catch (final SQLException e) {
			LOGGER.log(Level.SEVERE, "cannot prepare database statement", e);
			throw new ServletException("cannot connect to database");
		}
	}

	public void enqueue(final Entry<LoginTuple, Counter> notification) {
		// if this fails, we lose a count
		updateQueue.offer(notification);
	}

	public void shutdown() {
		try {
			updateQueue.put(END);
			LOGGER.info("queue shutdown in progress");
			join();
			LOGGER.info("queue shutdown finished");
		} catch (final InterruptedException e) {
			LOGGER.log(Level.SEVERE, "graceful queue shutdown failed", e);
			// graceful shutdown failed. try ungraceful shutdown.
			// this will lose counts.
			interrupt();
		}
	}

	@Override
	public void run() {
		while (!interrupted()) {
			Entry<LoginTuple, Counter> counter;
			try {
				counter = updateQueue.take();
			} catch (final InterruptedException e) {
				break;
			}
			if (counter == END)
				break;
			updateCount(counter);
		}

		db.close();
	}

	private void updateCount(final Entry<LoginTuple, Counter> counter) {
		try {
			tryUpdateCount(counter);
			return;
		} catch (final SQLException e) {
			LOGGER.log(Level.WARNING,
					"failed to update counts for " + counter.getKey()
							+ "; will retry", e);
		}

		// first attempt failed; database connection was somehow broken. let's
		// try again; the statement will try to reconnect itself if possible.
		try {
			tryUpdateCount(counter);
		} catch (final SQLException e) {
			// second attempt failed as well, ie. reconnecting failed. this
			// means the database is probably down; there is no point trying to
			// reconnect any further. perhaps the next database connection will
			// succeed again.
			LOGGER.log(Level.SEVERE,
					"failed to update counts for " + counter.getKey()
							+ "; database down?", e);
		}
	}

	private void tryUpdateCount(final Entry<LoginTuple, Counter> counter)
			throws SQLException {
		stmt.prepareStatement();
		stmt.setInt(1, counter.getKey().getIpHash());
		stmt.setString(2, counter.getKey().getEntityID());
		stmt.setInt(3, counter.getValue().get());
		stmt.executeUpdate();
	}
}
