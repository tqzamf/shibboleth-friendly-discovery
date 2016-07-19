package de.uniKonstanz.shib.disco.metadata;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.annotate.JsonProperty;

/**
 * Data class to hold IdP metadata deserialized from the JSON DiscoFeed. Note
 * that only the entityID is actually extracted; everything else is directly
 * read from the XML metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class IdP {
	@JsonProperty
	public String entityID;
}
