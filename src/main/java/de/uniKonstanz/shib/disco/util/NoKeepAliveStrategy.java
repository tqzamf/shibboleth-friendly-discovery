package de.uniKonstanz.shib.disco.util;

import org.apache.http.HttpResponse;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.protocol.HttpContext;

/**
 * A {@link ConnectionKeepAliveStrategy} that kills collections immediately.
 * This avoids keeping around connections that won't be reused anyway.
 */
public class NoKeepAliveStrategy implements ConnectionKeepAliveStrategy {
	public long getKeepAliveDuration(final HttpResponse response,
			final HttpContext context) {
		return 0;
	}
}
