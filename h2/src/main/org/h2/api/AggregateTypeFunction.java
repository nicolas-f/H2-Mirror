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
     * This method must return the h2 SQL type of the method, given the h2 SQL type of
     * the input data. The method should check here if the number of parameters
     * passed is correct, and if not it should throw an exception.
     *
     * @param inputTypes the H2 SQL type of the parameters, {@link org.h2.value.Value}
     * @return the h2 internal SQL type of the result
     * @throws SQLException if the number/type of parameters passed is incorrect
     */
    int getInternalType(int[] inputTypes) throws SQLException;
}
