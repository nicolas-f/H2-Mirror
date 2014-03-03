/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.value;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;

import com.vividsolutions.jts.geom.CoordinateSequence;
import com.vividsolutions.jts.geom.CoordinateSequenceFilter;
import com.vividsolutions.jts.geom.PrecisionModel;
import com.vividsolutions.jts.io.ByteArrayInStream;
import com.vividsolutions.jts.io.ByteOrderDataInStream;
import com.vividsolutions.jts.io.ByteOrderValues;
import com.vividsolutions.jts.io.WKBConstants;
import org.h2.constant.ErrorCode;
import org.h2.message.DbException;
import org.h2.util.GeometryMetaData;
import org.h2.util.StringUtils;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKBReader;
import com.vividsolutions.jts.io.WKBWriter;
import com.vividsolutions.jts.io.WKTReader;
import com.vividsolutions.jts.io.WKTWriter;

/**
 * Implementation of the GEOMETRY data type.
 *
 * @author Thomas Mueller
 * @author Noel Grandin
 * @author Nicolas Fortin, Atelier SIG, IRSTV FR CNRS 24888
 */
public class ValueGeometry extends Value {
    public static final int GEOMETRY = 0;
    public static final int POINT = 1;
    public static final int LINESTRING = 2;
    public static final int POLYGON = 3;
    public static final int MULTIPOINT = 4;
    public static final int MULTILINESTRING = 5;
    public static final int MULTIPOLYGON = 6;
    public static final int GEOMCOLLECTION = 7;
    public static final int CURVE = 13;
    public static final int SURFACE = 14;
    public static final int POLYHEDRALSURFACE = 15;
    public static final int GEOMETRYZ = 1000;
    public static final int POINTZ = POINT + 1000;
    public static final int LINESTRINGZ = LINESTRING + 1000;
    public static final int POLYGONZ = POLYGON + 1000;
    public static final int MULTIPOINTZ = MULTIPOINT + 1000;
    public static final int MULTILINESTRINGZ = MULTILINESTRING + 1000;
    public static final int MULTIPOLYGONZ = MULTIPOLYGON + 1000;
    public static final int GEOMCOLLECTIONZ = GEOMCOLLECTION + 1000;
    public static final int CURVEZ = CURVE + 1000;
    public static final int SURFACEZ = SURFACE + 1000;
    public static final int POLYHEDRALSURFACEZ = POLYHEDRALSURFACE + 1000;
    public static final int GEOMETRYM = 2000;
    public static final int POINTM = POINT + 2000;
    public static final int LINESTRINGM = LINESTRING + 2000;
    public static final int POLYGONM = POLYGON + 2000;
    public static final int MULTIPOINTM = MULTIPOINT + 2000;
    public static final int MULTILINESTRINGM = MULTILINESTRING + 2000;
    public static final int MULTIPOLYGONM = MULTIPOLYGON + 2000;
    public static final int GEOMCOLLECTIONM = GEOMCOLLECTION + 2000;
    public static final int CURVEM = CURVE + 2000;
    public static final int SURFACEM = SURFACE + 2000;
    public static final int POLYHEDRALSURFACEM = POLYHEDRALSURFACE + 2000;
    public static final int GEOMETRYZM = 3000;
    public static final int POINTZM = POINT + 3000;
    public static final int LINESTRINGZM = LINESTRING + 3000;

    /**
     * As conversion from/to WKB cost a significant amount of CPU cycles, WKB
     * are kept in ValueGeometry instance.
     * 
     * We always calculate the WKB, because not all WKT values can be
     * represented in WKB, but since we persist it in WKB format, it has to be
     * valid in WKB
     */
    private final byte[] bytes;

    private final int hashCode;
    
    /**
     * The value. Converted from WKB only on request as conversion from/to WKB
     * cost a significant amount of CPU cycles.
     */
    private Geometry geometry;

    private int srid_constraint = 0;

    /**
     * Create a new geometry objects.
     * 
     * @param bytes the bytes (always known)
     * @param geometry the geometry object (may be null)
     */
    private ValueGeometry(byte[] bytes, Geometry geometry) {
        this.bytes = bytes;
        this.geometry = geometry;
        this.hashCode = Arrays.hashCode(bytes);
    }

    /**
     * Get or create a geometry value for the given geometry.
     *
     * @param o the geometry object (of type com.vividsolutions.jts.geom.Geometry)
     * @return the value
     */
    public static ValueGeometry getFromGeometry(Object o) {
        return get((Geometry) o);
    }

    private static ValueGeometry get(Geometry g) {
        byte[] bytes = convertToWKB(g);
        return (ValueGeometry) Value.cache(new ValueGeometry(bytes, g));
    }
    
    private static byte[] convertToWKB(Geometry g) {
        boolean includeSRID = g.getSRID() != 0;
        int dimensionCount = getDimensionCount(g);
        WKBWriter writer = new WKBWriter(dimensionCount, includeSRID);
        return writer.write(g);        
    }

    @Override
    public boolean checkPrecision(long geometryType) {
        if(geometryType < Integer.MAX_VALUE && geometryType != 0 && getBytes() != null) {
            // If there is a Geometry type constraint
            try {
                int typeCode = GeometryMetaData.getMetaDataFromWKB(getBytesNoCopy()).geometryType;
                if(typeCode != geometryType) {
                    throw DbException.get(ErrorCode.GEOMETRY_TYPE_CONSTRAINT_VIOLATION, "Expected geometry type code "+geometryType+" found "+typeCode);
                }
            } catch (IOException ex) {
                throw DbException.convertIOException(ex, "Wrong WKB");
            }
        }
        return true;
    }

    /**
     * Check geometry SRID. If the geometry SRID does not comply with Column constraint then an exception is raised.
     * @param onlyToSmallerScale if the scale should not reduced
     * @param targetScale the requested scale
     * @return this
     */
    @Override
    public Value convertScale(boolean onlyToSmallerScale, int targetScale) {
        srid_constraint = targetScale;
        try {
            if(srid_constraint!=0 && srid_constraint!=Integer.MAX_VALUE && getBytesNoCopy()!=null
                    && GeometryMetaData.getMetaDataFromWKB(getBytesNoCopy()).SRID != srid_constraint) {
                        throw DbException.get(ErrorCode.GEOMETRY_SRID_CONSTRAINT_VIOLATION,
                                Integer.toString(srid_constraint), Integer.toString(srid_constraint));
            }
        } catch (IOException ex) {
            throw DbException.convertIOException(ex, "Wrong WKB");
        }
        return this;
    }

    private static int getDimensionCount(Geometry geometry) {
        ZVisitor finder = new ZVisitor();
        geometry.apply(finder);
        return finder.isFoundZ() ? 3 : 2;
    }
    
    /**
     * Get or create a geometry value for the given geometry.
     *
     * @param s the WKT representation of the geometry
     * @return the value
     */
    public static ValueGeometry get(String s) {
        return get(s, 0);
    }

    /**
     * Get or create a geometry value for the given geometry.
     *
     * @param s the WKT representation of the geometry
     * @return the value
     */
    public static ValueGeometry get(String s,int srid) {
        try {
            Geometry g = new WKTReader(new GeometryFactory(new PrecisionModel(),srid)).read(s);
            return get(g);
        } catch (ParseException ex) {
            throw DbException.convert(ex);
        }
    }

    /**
     * Get or create a geometry value for the given geometry.
     *
     * @param bytes the WKB representation of the geometry
     * @return the value
     */
    public static ValueGeometry get(byte[] bytes) {
        return (ValueGeometry) Value.cache(new ValueGeometry(bytes, null));
    }

    public Geometry getGeometry() {
        if (geometry == null) {
            try {
                geometry = new WKBReader().read(bytes);
                if(srid_constraint > 0 && srid_constraint < Integer.MAX_VALUE) {
                    int srid = geometry.getSRID();
                    if(srid == 0) {
                        geometry.setSRID(srid_constraint);
                    } else if(srid != srid_constraint) {
                        throw DbException.get(ErrorCode.GEOMETRY_SRID_CONSTRAINT_VIOLATION, "");
                    }
                }
            } catch (ParseException ex) {
                throw DbException.convert(ex);
            }
        }
        return geometry;
    }
    
    /**
     * Test if this geometry envelope intersects with the other geometry
     * envelope.
     *
     * @param r the other geometry
     * @return true if the two overlap
     */
    public boolean intersectsBoundingBox(ValueGeometry r) {
        // the Geometry object caches the envelope
        return getGeometry().getEnvelopeInternal().intersects(
                r.getGeometry().getEnvelopeInternal());
    }

    /**
     * Get the union.
     *
     * @param r the other geometry
     * @return the union of this geometry envelope and another geometry envelope
     */
    public Value getEnvelopeUnion(ValueGeometry r) {
        GeometryFactory gf = new GeometryFactory();
        Envelope mergedEnvelope = new Envelope(getGeometry().getEnvelopeInternal());
        mergedEnvelope.expandToInclude(r.getGeometry().getEnvelopeInternal());
        return get(gf.toGeometry(mergedEnvelope));
    }

    /**
     * Get the intersection.
     *
     * @param r the other geometry
     * @return the intersection of this geometry envelope and another
     */
    public ValueGeometry getEnvelopeIntersection(ValueGeometry r) {
        Envelope e1 = getGeometry().getEnvelopeInternal();
        Envelope e2 = r.getGeometry().getEnvelopeInternal();
        Envelope e3 = e1.intersection(e2);
        // try to re-use the object
        if (e3 == e1) {
            return this;
        } else if (e3 == e2) {
            return r;
        }
        GeometryFactory gf = new GeometryFactory();
        return get(gf.toGeometry(e3));
    }

    @Override
    public int getType() {
        return Value.GEOMETRY;
    }

    @Override
    public String getSQL() {
        // WKT does not hold Z or SRID with JTS 1.13. As getSQL is used to
        // export database, it should contains all object attributes. Moreover
        // using bytes is faster than converting WKB to Geometry then to WKT.
        return "X'" + StringUtils.convertBytesToHex(getBytesNoCopy()) + "'::Geometry";
    }

    @Override
    protected int compareSecure(Value v, CompareMode mode) {
        Geometry g = ((ValueGeometry) v).getGeometry();
        return getGeometry().compareTo(g);
    }

    @Override
    public String getString() {
        return getWKT();
    }

    @Override
    public long getPrecision() {
        return 0;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public Object getObject() {
        return getGeometry();
    }

    @Override
    public byte[] getBytes() {
        return getWKB();
    }

    @Override
    public byte[] getBytesNoCopy() {
        return getWKB();
    }

    @Override
    public void set(PreparedStatement prep, int parameterIndex)
            throws SQLException {
        prep.setObject(parameterIndex, getGeometry());
    }

    @Override
    public int getDisplaySize() {
        return getWKT().length();
    }

    @Override
    public int getMemory() {
        return getWKB().length * 20 + 24;
    }

    @Override
    public boolean equals(Object other) {
        // The JTS library only does half-way support for 3D coordinates, so
        // their equals method only checks the first two coordinates.
        return other instanceof ValueGeometry && 
                Arrays.equals(getWKB(), ((ValueGeometry) other).getWKB());
    }

    /**
     * Get the value in Well-Known-Text format.
     *
     * @return the well-known-text
     */
    public String getWKT() {
        return new WKTWriter().write(getGeometry());
    }

    /**
     * Get the value in Well-Known-Binary format.
     *
     * @return the well-known-binary
     */
    public byte[] getWKB() {
        return bytes;
    }

    @Override
    public Value convertTo(int targetType) {
        if (targetType == Value.JAVA_OBJECT) {
            return this;
        }
        return super.convertTo(targetType);
    }

    /**
     * A visitor that checks if there is a Z coordinate.
     */
    static class ZVisitor implements CoordinateSequenceFilter {
        boolean foundZ;

        public boolean isFoundZ() {
            return foundZ;
        }

        @Override
        public void filter(CoordinateSequence coordinateSequence, int i) {
            if (!Double.isNaN(coordinateSequence.getOrdinate(i, 2))) {
                foundZ = true;
            }
        }

        @Override
        public boolean isDone() {
            return foundZ;
        }

        @Override
        public boolean isGeometryChanged() {
            return false;
        }

    }

}
