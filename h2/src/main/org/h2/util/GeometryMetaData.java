/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.util;

import com.vividsolutions.jts.io.ByteArrayInStream;
import com.vividsolutions.jts.io.ByteOrderDataInStream;
import com.vividsolutions.jts.io.ByteOrderValues;
import com.vividsolutions.jts.io.WKBConstants;

import java.io.IOException;

/**
 * Extract Geometry MetaData from WKB.
 * WKB Conversion source from {@link com.vividsolutions.jts.io.WKBReader}
 * @author Nicolas Fortin, Atelier SIG, IRSTV FR CNRS 24888
 */
public class GeometryMetaData {
    /** If SRID is available */
    public final boolean hasSRID;
    /** If Z Component is available */
    public final boolean hasZ;
    /** Geometry type code */
    public final int geometryType;
    /** Geometry dimension 2 or 3 */
    public final int dimension;
    /** Projection code */
    public final int SRID;

    private GeometryMetaData(int dimension, boolean hasSRID, boolean hasZ, int geometryType, int SRID) {
        this.dimension = dimension;
        this.hasSRID = hasSRID;
        this.hasZ = hasZ;
        this.geometryType = geometryType;
        this.SRID = SRID;
    }

    /**
     * Read the first bytes of Geometry WKB.
     * @param bytes WKB Bytes
     * @return Geometry MetaData
     * @throws java.io.IOException If WKB meta is invalid (do not check the Geometry)
     */
    public static GeometryMetaData getMetaDataFromWKB(byte[] bytes) throws IOException {
        ByteOrderDataInStream dis = new ByteOrderDataInStream();
        dis.setInStream(new ByteArrayInStream(bytes));
        // determine byte order
        byte byteOrderWKB = dis.readByte();
        // always set byte order, since it may change from geometry to geometry
        int byteOrder = byteOrderWKB == WKBConstants.wkbNDR ? ByteOrderValues.LITTLE_ENDIAN : ByteOrderValues.BIG_ENDIAN;
        dis.setOrder(byteOrder);

        int typeInt = dis.readInt();
        int geometryType = typeInt & 0xff;
        // determine if Z values are present
        boolean hasZ = (typeInt & 0x80000000) != 0;
        int inputDimension =  hasZ ? 3 : 2;
        // determine if SRIDs are present
        boolean hasSRID = (typeInt & 0x20000000) != 0;

        int SRID = 0;
        if (hasSRID) {
            SRID = dis.readInt();
        }
        return new GeometryMetaData(inputDimension, hasSRID, hasZ, geometryType, SRID);
    }
}