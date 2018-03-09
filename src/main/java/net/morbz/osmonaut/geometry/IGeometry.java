package net.morbz.osmonaut.geometry;

/*
* The MIT License (MIT)
* 
* Copyright (c) 2015 Merten Peetz
* 
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
* 
* The above copyright notice and this permission notice shall be included in all
* copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
* SOFTWARE.
*/

import net.morbz.osmonaut.osm.LatLon;

/**
 * The interface for all geometries.
 *
 * @author poseidon
 */
public interface IGeometry {

	/**
	 * @return The geometric centroid of this polygon or null if there are no coordinates
	 */
	public LatLon getCenter();

	/**
	 * Returns a bounding box that contains all points of this polygon.
	 *
	 * @return The surrounding bounding box
	 */
	public Bounds getBounds();

	/**
	 * The inherent dimension of this Geometry object, which must be less than or equal to the coordinate dimension.
	 * OGC SPEC s2.1.1.1 - returns 0 for POINT, 1 for LINESTRING, 2 for POLYGON, and the largest dimension of the
	 * components of a GEOMETRYCOLLECTION. If unknown (empty geometry) null is returned.
	 * @return the dimension of this geometry
	 */
	public Integer getDimension();
}
