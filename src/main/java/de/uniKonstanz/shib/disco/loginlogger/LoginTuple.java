package de.uniKonstanz.shib.disco.loginlogger;

import de.uniKonstanz.shib.disco.metadata.IdPMeta;
import de.uniKonstanz.shib.disco.metadata.XPMeta;

/**
 * Represents a combination of IdP and nethash for logging of logins, and keeps
 * track of the number of logins for that combination.
 */
public final class LoginTuple {
	private final int ipHash;
	private final String entityID;
	private int count;

	/**
	 * @param ipHash
	 *            client network hash
	 * @param idp
	 *            the IdP that was chosen, represented by its {@link IdPMeta}
	 *            object
	 */
	public LoginTuple(final int ipHash, final XPMeta idp) {
		this.ipHash = ipHash;
		// note that this is the same String object for every login. this
		// avoids holding many copies of identical strings just because they
		// were read from different places.
		entityID = idp.getEntityID();
	}

	/**
	 * Gets the current counter value.
	 * 
	 * @return current value
	 */
	public final int getCount() {
		return count;
	}

	/**
	 * Increments the counter value by 1.
	 */
	public final void incrementCounter() {
		// unsynchronized, ie. might lose counts. if this happens frequently,
		// there must be many counts for this combination, and then losing some
		// is ok. if it doesn't happen frequently, then there is no problem to
		// begin with.
		count++;
	}

	@Override
	public boolean equals(final Object obj) {
		if (!(obj instanceof LoginTuple))
			return false;
		final LoginTuple other = (LoginTuple) obj;
		if (other.getIpHash() != getIpHash())
			return false;
		return other.getEntityID().equals(getEntityID());
	}

	@Override
	public int hashCode() {
		return getEntityID().hashCode() ^ getIpHash();
	}

	public int getIpHash() {
		return ipHash;
	}

	public String getEntityID() {
		return entityID;
	}

	@Override
	public String toString() {
		return entityID + "#" + ipHash;
	}
}