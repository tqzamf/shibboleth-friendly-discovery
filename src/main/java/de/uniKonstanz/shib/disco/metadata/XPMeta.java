package de.uniKonstanz.shib.disco.metadata;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import de.uniKonstanz.shib.disco.AbstractShibbolethServlet;

/**
 * Data class to represent an SP or IdP in memory. Equality and sort order
 * defined by entityID alone.
 */
class XPMeta<T extends XPMeta<T>> implements Comparable<T> {
	protected final String entityID;
	private final String encEntityID;

	public XPMeta(final String entityID) {
		this.entityID = entityID;
		try {
			encEntityID = URLEncoder.encode(entityID,
					AbstractShibbolethServlet.ENCODING);
		} catch (final UnsupportedEncodingException e) {
			throw new RuntimeException("no support for "
					+ AbstractShibbolethServlet.ENCODING, e);
		}
	}

	/**
	 * Gets the plain entityID. This is unsafe to include in query parameters.
	 * 
	 * @return the entityID
	 */
	public String getEntityID() {
		return entityID;
	}

	/**
	 * Gets the URL-encoded entityID. It is safely escaped for inclusion in
	 * query parameters.
	 * 
	 * @return the entityID
	 */
	public String getEncodedEntityID() {
		return encEntityID;
	}

	@Override
	public int hashCode() {
		return entityID.hashCode();
	}

	@Override
	public boolean equals(final Object o) {
		@SuppressWarnings("unchecked")
		final XPMeta<T> m = (XPMeta<T>) o;
		return entityID.equals(m.entityID);
	}

	@Override
	public int compareTo(final T other) {
		return entityID.compareTo(other.getEntityID());
	}

	@Override
	public String toString() {
		return entityID;
	}
}
