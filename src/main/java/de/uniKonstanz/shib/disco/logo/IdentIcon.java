package de.uniKonstanz.shib.disco.logo;

/**
 * IdentIcon aka CarpetMaker for Java.
 * Copyright (C) 2015  Matthias Fratz
 * 
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110, USA 
 */

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Identification icon generator. Aka. carpet maker because the result looks
 * like a carpet when it isn't square.
 */
public class IdentIcon {
	private static final Color[] palette = {
			// reasonably distinguishable colors
			new Color(64, 64, 64), // gray
			new Color(228, 3, 3), // red
			new Color(255, 140, 0), // orange
			new Color(240, 224, 0), // dark-ish yellow
			new Color(0, 128, 38), // green
			new Color(0, 77, 255), // blue
			new Color(117, 7, 135), // violet
			new Color(0, 180, 180), // cyan
	};
	private final int dim;
	private final Point TL, TC, TR;
	private final Point CL, CC, CR;
	private final Point BL, BC, BR;
	private final int w;
	private final int h;

	/**
	 * Creates a new Ident Icon Factory.
	 * 
	 * @param dim
	 *            size of constituent square
	 * @param w
	 *            width in squares
	 * @param h
	 *            height in squares
	 */
	public IdentIcon(final int dim, final int w, final int h) {
		this.dim = dim;
		this.w = w;
		this.h = h;
		TL = new Point(0, 0);
		TC = new Point(dim / 2, 0);
		TR = new Point(dim, 0);
		CL = new Point(0, dim / 2);
		CC = new Point(dim / 2, dim / 2);
		CR = new Point(dim, dim / 2);
		BL = new Point(0, dim);
		BC = new Point(dim / 2, dim);
		BR = new Point(dim, dim);
	}

	/**
	 * Generates a basic icon, which in general will be asymmetric.
	 * 
	 * @param ident
	 *            hex-encoded string to visualize
	 * @return the icon as an RGB {@link BufferedImage}
	 */
	public BufferedImage getBaseIcon(final String ident) {
		final BufferedImage img = new BufferedImage(dim * w, dim * h,
				BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);

		// decide color and initialize bit generator
		int acc = Character.digit(ident.charAt(0), 16);
		int num = 1;
		int pos = 1;
		final int color = acc & 7;
		acc >>= 3;
		num -= 3;
		for (int x = 0; x < w; x++)
			for (int y = 0; y < h; y++) {
				// this re-formats hex characters into 5-bit units
				while (num < 6) {
					final int digit = Character.digit(ident.charAt(pos++), 16);
					acc |= digit << num;
					num += 4;
				}
				final int bits = acc & 63;
				acc >>= 6;
				num -= 6;

				// bit 0x10 inverts the color of the current rectangle
				final AffineTransform tfm = g.getTransform();
				g.translate(dim * x, dim * y);
				// switch (bits & 16) {
				switch (bits & 32) {
				case 0:
					g.setBackground(Color.WHITE);
					g.setColor(palette[color]);
					break;
				// case 16:
				case 32:
					g.setBackground(palette[color]);
					g.setColor(Color.WHITE);
					break;
				}
				g.clearRect(0, 0, dim, dim);
				// generally, bits 0xC0 decide about rotation, however some of
				// the symbols have rotational symmetries, so different ones
				// have to be used for 180° rotation.
				switch (bits & 3) {
				case 3:
					g.rotate(Math.PI / 2, dim / 2.0, dim / 2.0);
				case 2:
					g.rotate(Math.PI / 2, dim / 2.0, dim / 2.0);
				case 1:
					g.rotate(Math.PI / 2, dim / 2.0, dim / 2.0);
				case 0:
					break;
				}
				// carefully chosen symbols to generate "nice" icons / carpets.
				// these are ordered so that symbols & 0x04 == 0 are symmetric.
				// switch (bits & 15) {
				switch (bits & 31) {
				case 0: // triangle at top pointing up
				case 1:
				case 2:
				case 3:
					poly(g, CL, TC, CR);
					break;
				case 4: // triangle at bottom pointing up
				case 5:
				case 6:
				case 7:
					poly(g, BL, CC, BR);
					break;
				case 8: // rectangle in top-left corner
				case 9:
				case 10:
				case 11:
					poly(g, TL, TC, CC, CL);
					break;
				case 12: // sort of an arrow pointing into the top-left corner
				case 13:
				case 14:
				case 15:
					poly(g, TL, TC, CR, CC, BC, CL);
					break;
				case 16: // most easily described as a rhombus with the triangle
				case 17: // in the top-right corner filled in
				case 18:
				case 19:
					poly(g, TL, TC, CR, BC, CL);
					break;
				case 20: // corner-pointing triangles in adjacent corners
				case 21:
				case 22:
				case 23:
					poly(g, TL, TC, CL);
					poly(g, TC, TR, CR);
					break;
				case 24: // top half filled (symmetric wrt 180° rotation)
				case 25:
					poly(g, TL, TR, CR, CL);
					break;
				case 26:
				case 27: // main diagonal (symmetric wrt 180° rotation)
					poly(g, TL, TR, BL);
					break;
				case 28: // corner-pointing triangles in opposite corners (180°
				case 29: // symmetry)
					poly(g, TL, TC, CR, BR, BC, CL);
					break;
				case 30: // rhombus (90° symmetry)
					poly(g, TC, CR, BC, CR);
					break;
				case 31: // diagonal blocks (90° symmetry when inverted)
					poly(g, TL, TC, CC, CL);
					poly(g, TC, TR, CR, CC);
					break;
				}
				g.setTransform(tfm);
			}
		g.dispose();
		return img;
	}

	/** Polygon drawing helper. */
	private static void poly(final Graphics2D g, final Point... points) {
		final int[] x = new int[points.length];
		final int[] y = new int[points.length];
		for (int i = 0; i < points.length; i++) {
			x[i] = points[i].x;
			y[i] = points[i].y;
		}
		g.fillPolygon(x, y, points.length);
	}

	/**
	 * Generates an icon that has been mirrored in both directions, and thus has
	 * 90° rotational symmetry. If it isn't square, it will look like a carpet.
	 * 
	 * @param ident
	 *            hex-encoded string to visualize
	 * @return the icon as an RGB {@link BufferedImage}
	 */
	public BufferedImage getSymmetricalIcon(final String ident) {
		final BufferedImage img = getBaseIcon(ident);
		final int w = img.getWidth();
		final int h = img.getHeight();
		final BufferedImage dup = new BufferedImage(2 * w, 2 * h,
				BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = dup.createGraphics();
		g.drawImage(img, 0, 0, w, h, null);
		g.drawImage(img, 2 * w, 0, -w, h, null);
		g.drawImage(img, 0, 2 * h, w, -h, null);
		g.drawImage(img, 2 * w, 2 * h, -w, -h, null);
		g.dispose();
		return dup;
	}
}
