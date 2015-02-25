package de.uniKonstanz.shib.disco.loginlogger;

public final class LoginTuple {
	private final int ipHash;
	private final String entityID;

	public LoginTuple(final int ipHash, final String entityID) {
		this.ipHash = ipHash;
		this.entityID = entityID;
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