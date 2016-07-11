package de.uniKonstanz.shib.disco.metadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class SPMeta extends XPMeta<SPMeta> {
	private ArrayList<String> locations;
	private String defaultReturn;

	public SPMeta(final String entityID) {
		super(entityID);
	}

	public void setReturnLocations(final Collection<String> locations) {
		this.locations = new ArrayList<>(locations);
	}

	public List<String> getReturnLocations() {
		return locations;
	}

	public void setDefaultReturn(final String defaultReturn) {
		this.defaultReturn = defaultReturn;
	}

	public String getDefaultReturn() {
		return defaultReturn;
	}

	@Override
	public String toString() {
		return super.toString() + ": " + defaultReturn;
	}
}
