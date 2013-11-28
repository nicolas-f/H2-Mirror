/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.api;

import java.sql.SQLException;

/**
 * A user-defined aggregate function needs to implement this interface.
 * The class must be public and must have a public non-argument constructor.
 */
public interface AggregateTypeFunction extends AggregateAlias {

    /**
     * This method must return the SQL type of the method, given the SQL type of
     * the input data. The method should check here if the number of parameters
     * passed is correct, and if not it should throw an exception.
     *
     * @param inputTypes the SQL type of the parameters, {@link java.sql.Types}
     * @param inputTypesName the SQL type name of the parameters  {@link java.sql.ResultSetMetaData#getColumnTypeName(int)}
     * @return the SQL type of the result
     */
    ColumnType getType(int[] inputTypes, String[] inputTypesName) throws SQLException;

    /**
     * Expected aggregate return type
     */
    public class ColumnType {
        public final int type;
        public final String typeName;

        /**
         * Constructor.
         * @param type the SQL type {@link java.sql.Types}
         * @param typeName the SQL type name. {@link java.sql.ResultSetMetaData#getColumnTypeName(int)}
         */
        public ColumnType(int type, String typeName) {
            this.type = type;
            this.typeName = typeName;
        }
    }
}
