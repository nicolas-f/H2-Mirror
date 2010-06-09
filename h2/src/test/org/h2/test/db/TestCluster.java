/*
 * Copyright 2004-2010 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.test.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.h2.test.TestBase;
import org.h2.tools.CreateCluster;
import org.h2.tools.DeleteDbFiles;
import org.h2.tools.Server;
import org.h2.util.IOUtils;
import org.h2.util.JdbcUtils;

/**
 * Test for the cluster feature.
 */
public class TestCluster extends TestBase {

    /**
     * Run just this test.
     *
     * @param a ignored
     */
    public static void main(String... a) throws Exception {
        TestBase.createCaller().init().test();
    }

    public void test() throws Exception {
        testCreateClusterAtRuntime();
        testStartStopCluster();
    }

    private void testCreateClusterAtRuntime() throws SQLException {
        if (config.memory || config.networked || config.cipher != null) {
            return;
        }
        int port1 = 9191, port2 = 9192;
        String serverList = "localhost:" + port1 + ",localhost:" + port2;
        deleteFiles();

        org.h2.Driver.load();
        String user = getUser(), password = getPassword();
        Connection conn;
        Statement stat;
        String url1 = "jdbc:h2:tcp://localhost:" + port1 + "/test";
        String url2 = "jdbc:h2:tcp://localhost:" + port2 + "/test";
        String urlCluster = "jdbc:h2:tcp://" + serverList + "/test";
        int len = 10;

        // initialize the database
        Server n1 = org.h2.tools.Server.createTcpServer("-tcpPort", "" + port1, "-baseDir", getBaseDir() + "/node1").start();
        conn = DriverManager.getConnection(url1, user, password);
        stat = conn.createStatement();
        stat.execute("create table test(id int primary key, name varchar) as " +
                "select x, 'Data' || x from system_range(0, " + (len - 1) + ")");
        stat.execute("create user test password 'test'");
        stat.execute("grant all on test to test");

        // start the second server
        Server n2 = org.h2.tools.Server.createTcpServer("-tcpPort", "" + port2 , "-baseDir", getBaseDir() + "/node2").start();

        // copy the database and initialize the cluster
        CreateCluster.main("-urlSource", url1, "-urlTarget", url2, "-user", user, "-password", password, "-serverList",
                serverList);

        // check the original connection is closed
        try {
            stat.execute("select * from test");
            fail();
        } catch (SQLException e) {
            // expected
            JdbcUtils.closeSilently(conn);
        }

        // test the cluster connection
        Connection connApp = DriverManager.getConnection(urlCluster + ";AUTO_RECONNECT=TRUE", user, password);
        check(connApp, len, "'" + serverList + "'");

        // delete the rows, but don't commit
        connApp.setAutoCommit(false);
        connApp.createStatement().execute("delete from test");

        // stop server 2, and test if only one server is available
        n2.stop();

        // rollback the transaction
        connApp.createStatement().executeQuery("select count(*) from test");
        connApp.rollback();
        check(connApp, len, "''");
        connApp.setAutoCommit(true);

        // re-create the cluster
        n2 = org.h2.tools.Server.createTcpServer("-tcpPort", "" + port2, "-baseDir", getBaseDir() + "/node2").start();
        CreateCluster.main("-urlSource", url1, "-urlTarget", url2, "-user", user, "-password", password, "-serverList",
                serverList);

        // test the cluster connection
        check(connApp, len, "'" + serverList + "'");

        // test a non-admin user
        String user2 = "test", password2 = getPassword("test");
        connApp = DriverManager.getConnection(urlCluster, user2, password2);
        check(connApp, len, "'" + serverList + "'");

        n1.stop();
        
        // test non-admin cluster connection if only one server runs
        Connection connApp2 = DriverManager.getConnection(urlCluster + ";AUTO_RECONNECT=TRUE", user2, password2);
        check(connApp2, len, "''");
        connApp2.close();
        // test non-admin cluster connection if only one server runs
        connApp2 = DriverManager.getConnection(urlCluster + ";AUTO_RECONNECT=TRUE", user2, password2);
        check(connApp2, len, "''");
        connApp2.close();
        
        n2.stop();
        deleteFiles();
    }

    private void testStartStopCluster() throws SQLException {
        if (config.memory || config.networked || config.cipher != null) {
            return;
        }
        int port1 = 9193, port2 = 9194;
        String serverList = "localhost:" + port1 + ",localhost:" + port2;
        deleteFiles();

        // initialize the database
        Connection conn;
        org.h2.Driver.load();

        String urlNode1 = getURL("node1/test", true);
        String urlNode2 = getURL("node2/test", true);
        String user = getUser(), password = getPassword();
        conn = DriverManager.getConnection(urlNode1, user, password);
        Statement stat;
        stat = conn.createStatement();
        stat.execute("DROP TABLE IF EXISTS TEST");
        stat.execute("CREATE TABLE TEST(ID INT PRIMARY KEY, NAME VARCHAR(255))");
        PreparedStatement prep = conn.prepareStatement("INSERT INTO TEST VALUES(?, ?)");
        int len = getSize(10, 1000);
        for (int i = 0; i < len; i++) {
            prep.setInt(1, i);
            prep.setString(2, "Data" + i);
            prep.executeUpdate();
        }
        check(conn, len, "''");
        conn.close();

        // copy the database and initialize the cluster
        CreateCluster.main("-urlSource", urlNode1, "-urlTarget",
                urlNode2, "-user", user, "-password", password, "-serverList",
                serverList);

        // start both servers
        Server n1 = org.h2.tools.Server.createTcpServer("-tcpPort", "" + port1, "-baseDir", getBaseDir() + "/node1").start();
        Server n2 = org.h2.tools.Server.createTcpServer("-tcpPort", "" + port2, "-baseDir", getBaseDir() + "/node2").start();

        // try to connect in standalone mode - should fail
        try {
            conn = DriverManager.getConnection("jdbc:h2:tcp://localhost:"+port1+"/test", user, password);
            fail("should not be able to connect in standalone mode");
        } catch (SQLException e) {
            assertKnownException(e);
        }
        try {
            DriverManager.getConnection("jdbc:h2:tcp://localhost:"+port2+"/test", user, password);
            fail("should not be able to connect in standalone mode");
        } catch (SQLException e) {
            assertKnownException(e);
        }

        // test a cluster connection
        conn = DriverManager.getConnection("jdbc:h2:tcp://" + serverList + "/test", user, password);
        check(conn, len, "'"+serverList+"'");
        conn.close();

        // stop server 2, and test if only one server is available
        n2.stop();
        conn = DriverManager.getConnection("jdbc:h2:tcp://" + serverList + "/test", user, password);
        check(conn, len, "''");
        conn.close();
        conn = DriverManager.getConnection("jdbc:h2:tcp://" + serverList + "/test", user, password);
        check(conn, len, "''");
        conn.close();

        // disable the cluster
        conn = DriverManager.getConnection("jdbc:h2:tcp://localhost:"+port1+"/test;CLUSTER=''", user, password);
        conn.close();
        n1.stop();

        // re-create the cluster
        DeleteDbFiles.main("-dir", getBaseDir() + "/node2", "-quiet");
        CreateCluster.main("-urlSource", urlNode1, "-urlTarget",
                urlNode2, "-user", user, "-password", password, "-serverList",
                serverList);
        n1 = org.h2.tools.Server.createTcpServer("-tcpPort", "" + port1, "-baseDir", getBaseDir() + "/node1").start();
        n2 = org.h2.tools.Server.createTcpServer("-tcpPort", "" + port2, "-baseDir", getBaseDir() + "/node2").start();

        conn = DriverManager.getConnection("jdbc:h2:tcp://" + serverList + "/test", user, password);
        stat = conn.createStatement();
        stat.execute("CREATE TABLE BOTH(ID INT)");

        n1.stop();

        stat.execute("CREATE TABLE A(ID INT)");
        conn.close();
        n2.stop();

        n1 = org.h2.tools.Server.createTcpServer("-tcpPort", "" + port1, "-baseDir", getBaseDir() + "/node1").start();
        conn = DriverManager.getConnection("jdbc:h2:tcp://localhost:"+port1+"/test;CLUSTER=''", user, password);
        check(conn, len, "''");
        conn.close();
        n1.stop();

        n2 = org.h2.tools.Server.createTcpServer("-tcpPort", "" + port2, "-baseDir", getBaseDir() + "/node2").start();
        conn = DriverManager.getConnection("jdbc:h2:tcp://localhost:" + port2 + "/test;CLUSTER=''", user, password);
        check(conn, len, "''");
        conn.createStatement().execute("SELECT * FROM A");
        conn.close();
        n2.stop();
        deleteFiles();
    }

    private void deleteFiles() throws SQLException {
        DeleteDbFiles.main("-dir", getBaseDir() + "/node1", "-quiet");
        DeleteDbFiles.main("-dir", getBaseDir() + "/node2", "-quiet");
        IOUtils.delete(getBaseDir() + "/node1");
        IOUtils.delete(getBaseDir() + "/node2");
    }

    private void check(Connection conn, int len, String expectedCluster) throws SQLException {
        PreparedStatement prep = conn.prepareStatement("SELECT * FROM TEST WHERE ID=?");
        for (int i = 0; i < len; i++) {
            prep.setInt(1, i);
            ResultSet rs = prep.executeQuery();
            rs.next();
            assertEquals("Data" + i, rs.getString(2));
            assertFalse(rs.next());
        }
        ResultSet rs = conn.createStatement().executeQuery(
                "SELECT VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE NAME='CLUSTER'");
        rs.next();
        String cluster = rs.getString(1);
        assertEquals(expectedCluster, cluster);
    }

}
