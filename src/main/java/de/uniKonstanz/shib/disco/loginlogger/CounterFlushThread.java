package de.uniKonstanz.shib.disco.loginlogger;

import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.cache.LoadingCache;

/**
 * Background threads for {@link LoginServlet} that periodically flushes the
 * accumulated counts to the database. This happens automatically if the
 * {@link LoginServlet} is actively getting logins, but a separate flush is
 * required during idle time (night).
 */
public class CounterFlushThread extends Thread {
	private static final Logger LOGGER = Logger
			.getLogger(CounterFlushThread.class.getCanonicalName());
	/**
	 * Flush every 30 minutes. The counters expire after 10 minutes, but during
	 * idle time, it isn't too important to flush the exactly on time. All that
	 * is required is that they do get flushed eventually.
	 */
	private static final long INTERVAL = 30 * 60 * 1000;
	private final LoadingCache<LoginTuple, LoginTuple> counter;

	/**
	 * @param counter
	 *            the {@link LoadingCache} to flush periodically
	 */
	public CounterFlushThread(final LoadingCache<LoginTuple, LoginTuple> counter) {
		super("counter flush worker");
		this.counter = counter;
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
			LOGGER.log(Level.SEVERE, "counter flush thread shutdown failed", e);
		}
	}

	@Override
	public void run() {
		while (!interrupted()) {
			// perform cleanup. this causes the LoadingCache to expire timed-out
			// entries, which are then flushed to the database.
			counter.cleanUp();

			try {
				Thread.sleep(INTERVAL);
			} catch (final InterruptedException e1) {
				break;
			}
		}
	}
}
