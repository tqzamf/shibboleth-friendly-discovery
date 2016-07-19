package de.uniKonstanz.shib.disco.metadata;

import java.util.Iterator;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

class XPathNodeList {
	private static final XPath xpath = XPathFactory.newInstance().newXPath();
	static {
		xpath.setNamespaceContext(MetadataNamespaces.INSTANCE);
	}

	protected final XPathExpression expression;

	protected XPathNodeList(final String expression) {
		try {
			this.expression = xpath.compile(expression);
		} catch (final XPathExpressionException e) {
			// the expressions are hardcoded, so if they don't compile there
			// isn't much we can do about it
			throw new RuntimeException("cannot compile XPath " + expression, e);
		}
	}

	public NodeListIterable eval(final Node context)
			throws XPathExpressionException {
		return new NodeListIterable((NodeList) expression.evaluate(context,
				XPathConstants.NODESET));
	}

	public final class NodeListIterable implements Iterable<Element> {
		private final NodeList nodes;

		private NodeListIterable(final NodeList nodes) {
			this.nodes = nodes;
		}

		@Override
		public Iterator<Element> iterator() {
			return new NodeListIterator(nodes);
		}
	}

	private final class NodeListIterator implements Iterator<Element> {
		private final NodeList nodes;
		private int i;

		private NodeListIterator(final NodeList nodes) {
			this.nodes = nodes;
		}

		@Override
		public boolean hasNext() {
			return i < nodes.getLength();
		}

		@Override
		public Element next() {
			return (Element) nodes.item(i++);
		}
	}
}
