package de.uniKonstanz.shib.disco.metadata;

@SuppressWarnings("serial")
public class InconvertibleLogoException extends Exception {
	public InconvertibleLogoException(final String msg) {
		super(msg);
	}

	public InconvertibleLogoException(final String msg, final Throwable cause) {
		super(msg + ": " + cause.getClass().getCanonicalName() + ": "
				+ cause.getMessage(), cause);
	}
}
