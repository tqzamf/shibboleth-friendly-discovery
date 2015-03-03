package de.uniKonstanz.shib.disco.loginlogger;

/**
 * Simple, thread-safe counter.
 */
public final class Counter {
	private int i;

	/**
	 * Gets the current counter value.
	 * 
	 * @return current value
	 */
	public final synchronized int get() {
		final int temp = i;
		i = 0;
		return temp;
	}

	/**
	 * Increments the counter value by 1.
	 */
	public final synchronized void increment() {
		i++;
	}
}
