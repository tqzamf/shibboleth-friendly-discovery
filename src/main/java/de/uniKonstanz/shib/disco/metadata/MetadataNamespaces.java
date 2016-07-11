package de.uniKonstanz.shib.disco.metadata;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;

public class MetadataNamespaces implements NamespaceContext {
	public static final NamespaceContext INSTANCE = new MetadataNamespaces();

	private static final String IDPDISCO_NS = "urn:oasis:names:tc:SAML:profiles:SSO:idp-discovery-protocol";
	private static final String METADATA_NS = "urn:oasis:names:tc:SAML:2.0:metadata";
	private static final String METADATA_UI_NS = "urn:oasis:names:tc:SAML:metadata:ui";

	private static final Map<String, String> namespaces = new HashMap<String, String>();
	private static final Map<String, String> prefixes = new HashMap<String, String>();
	static {
		addNS(XMLConstants.XMLNS_ATTRIBUTE, XMLConstants.XMLNS_ATTRIBUTE_NS_URI);
		addNS(XMLConstants.XML_NS_PREFIX, XMLConstants.XML_NS_URI);
		addNS("idpdisco", IDPDISCO_NS);
		addNS("md", METADATA_NS);
		addNS("mdui", METADATA_UI_NS);
	}

	private static void addNS(final String prefix, final String namespace) {
		namespaces.put(prefix, namespace);
		prefixes.put(namespace, prefix);
	}

	private MetadataNamespaces() {
	}

	@Override
	public String getNamespaceURI(final String prefix) {
		if (!namespaces.containsKey(prefix))
			return XMLConstants.NULL_NS_URI;
		return namespaces.get(prefix);
	}

	@Override
	public String getPrefix(final String namespaceURI) {
		return prefixes.get(namespaceURI);
	}

	@Override
	public Iterator<String> getPrefixes(final String namespaceURI) {
		final String prefix = prefixes.get(namespaceURI);
		if (prefix == null)
			return Collections.emptyIterator();
		return Collections.singletonList(prefix).iterator();
	}
}
