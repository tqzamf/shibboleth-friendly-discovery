package de.uniKonstanz.shib.disco.loginlogger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import de.uniKonstanz.shib.disco.AbstractShibbolethServlet;
import de.uniKonstanz.shib.disco.util.ReconnectingBatchUpdate;
import de.uniKonstanz.shib.disco.util.ReconnectingDatabase;
import de.uniKonstanz.shib.disco.util.ReconnectingUpdate;

/**
 * Background threads for {@link LoginServlet} that asynchronously pushes the
 * collected counts to the database.
 */
public class DatabaseWorkerThread extends Thread {
	private static final Logger LOGGER = Logger
			.getLogger(DatabaseWorkerThread.class.getCanonicalName());
	private final BlockingQueue<Entry<LoginTuple, Counter>> updateQueue = new LinkedBlockingQueue<Entry<LoginTuple, Counter>>();
	/**
	 * Queue shutdown marker. Because Java doesn't provide one, and using
	 * <code>null</code> isn't allowed.
	 */
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
	private final ReconnectingUpdate<List<Entry<LoginTuple, Counter>>> stmt;

	/**
	 * @param db
	 *            the {@link ReconnectingDatabase} to push values to
	 * @throws ServletException
	 *             if the database statement cannot be prepared
	 */
	public DatabaseWorkerThread(final ReconnectingDatabase db)
			throws ServletException {
		super("login database worker");
		this.db = db;
		try {
			stmt = new ReconnectingBatchUpdate<List<Entry<LoginTuple, Counter>>>(
					db, "insert into"
							+ " loginstats(iphash, entityid, count, created)"
							+ " values(?, ?, ?, ?)") {
				@Override
				protected void exec(
						final List<Entry<LoginTuple, Counter>> counters)
						throws SQLException {
					setInt(4, AbstractShibbolethServlet.getCurrentDay());
					for (final Entry<LoginTuple, Counter> counter : counters) {
						setInt(1, counter.getKey().getIpHash());
						setString(2, counter.getKey().getEntityID());
						setInt(3, counter.getValue().get());
						addBatch();
					}
					executeUpdate();
				}
			};
		} catch (final SQLException e) {
			LOGGER.log(Level.SEVERE, "cannot prepare database statement", e);
			throw new ServletException("cannot connect to database");
		}
	}

	/**
	 * Enqueues a counter for upload to the database.
	 * 
	 * @param count
	 *            the {@link LoginTuple} and {@link Counter} describing the
	 *            count
	 */
	public void enqueue(final Entry<LoginTuple, Counter> count) {
		// if this fails, we lose a count. that's better than blocking the
		// request.
		updateQueue.offer(count);
	}

	/**
	 * Terminates the background thread. If possible, this will shut it down
	 * gracefully, after uploading any counts waiting in the queue. Will block
	 * until the queue has been shut down.
	 * 
	 * If something goes wrong, kills the thread directly, losing counts, and
	 * returns immediately.
	 */
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
			final List<Entry<LoginTuple, Counter>> counters;
			try {
				counters = takeNext();
			} catch (final InterruptedException e) {
				break;
			}
			updateCount(counters);
		}

		db.shutdown();
	}

	private List<Entry<LoginTuple, Counter>> takeNext()
			throws InterruptedException {
		final Entry<LoginTuple, Counter> counter = updateQueue.take();
		if (counter == END)
			throw new InterruptedException("end of queue");

		final List<Entry<LoginTuple, Counter>> counters = new ArrayList<Entry<LoginTuple, Counter>>();
		counters.add(counter);

		// there could be more than 1 element in the queue. if so, push them all
		// to the database in a single operation. this is more efficient than
		// pushing them one by one.
		while (true) {
			final Entry<LoginTuple, Counter> head = updateQueue.poll();
			if (head == null)
				break;
			if (head == END)
				// retain the end marker for next iteration; we still have to
				// push what we have collected so far.
				updateQueue.add(END);
			counters.add(head);
		}
		return counters;
	}

	/**
	 * Pushes a list of counters to the database, retrying the database
	 * operations if necessary.
	 */
	private void updateCount(final List<Entry<LoginTuple, Counter>> counters) {
		if (counters.isEmpty())
			return;

		try {
			stmt.executeUpdate(counters);
		} catch (final SQLException e) {
			// retry failed, ie. reconnecting failed. this means the database is
			// probably down; there is no point trying to reconnect any further.
			// perhaps the next database connection will succeed again.
			LOGGER.log(Level.SEVERE, "failed to update counts for "
					+ counters.get(0).getKey() + "; database down?", e);
		}
	}
}
