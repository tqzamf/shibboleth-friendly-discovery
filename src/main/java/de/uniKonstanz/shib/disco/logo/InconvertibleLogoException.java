package de.uniKonstanz.shib.disco.logo;

/**
 * Thrown by {@link LogoConverter} on "remote" failures, eg. when the logo
 * cannot be read or when conversion fails.
 */
@SuppressWarnings("serial")
public class InconvertibleLogoException extends Exception {
	public InconvertibleLogoException(final String msg) {
		super(msg);
	}

	public InconvertibleLogoException(final String msg, final Throwable cause) {
		// summarize cause in the exception message to allow abbreviated reports
		super(msg + ": " + cause.getClass().getCanonicalName() + ": "
				+ cause.getMessage(), cause);
	}
}
