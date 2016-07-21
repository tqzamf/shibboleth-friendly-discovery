package de.uniKonstanz.shib.disco.metadata;

import java.util.ArrayList;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

abstract class XPMetaParser {
	public abstract void update(final Document doc);

	protected static ArrayList<Element> getChildren(final Element node,
			final String tagname) {
		final NodeList nodes = node.getChildNodes();
		final ArrayList<Element> res = new ArrayList<Element>();
		for (int i = 0; i < nodes.getLength(); i++) {
			final Element n = (Element) nodes.item(i);
			if (n.getTagName().equals(tagname))
				res.add(n);
		}
		return res;
	}
}
