package de.uniKonstanz.shib.disco.loginlogger;

public final class Counter {
	private int i;

	public final synchronized int get() {
		final int temp = i;
		i = 0;
		return temp;
	}

	public final synchronized void increment() {
		i++;
	}
}
