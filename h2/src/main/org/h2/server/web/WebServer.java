/*
 * Copyright 2004-2008 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.server.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;

import org.h2.api.DatabaseEventListener;
import org.h2.constant.SysProperties;
import org.h2.engine.Constants;
import org.h2.message.TraceSystem;
import org.h2.server.Service;
import org.h2.server.ShutdownHandler;
import org.h2.util.ByteUtils;
import org.h2.util.FileUtils;
import org.h2.util.JdbcUtils;
import org.h2.util.MathUtils;
import org.h2.util.NetUtils;
import org.h2.util.RandomUtils;
import org.h2.util.Resources;
import org.h2.util.SortedProperties;

/**
 * The web server is a simple standalone HTTP server that implements the H2 Console application.
 * It is not optimized for performance.
 */
public class WebServer implements Service {

    private static final String DEFAULT_LANGUAGE = "en";

    private static final String[][] LANGUAGES = {
        { "de", "Deutsch" },
        { "en", "English" },
        { "es", "Espa\u00f1ol" },
        { "fr", "Fran\u00e7ais" },
        { "hu", "Magyar"},
        { "in", "Indonesia"},
        { "it", "Italiano"},
        { "ja", "\u65e5\u672c\u8a9e"},
        { "nl", "Nederlands"},
        { "pl", "Polski"},
        { "pt_BR", "Portugu\u00eas (Brasil)"},
        { "pt_PT", "Portugu\u00eas (Europeu)"},
        { "ru", "\u0440\u0443\u0441\u0441\u043a\u0438\u0439"},
        { "tr", "T\u00fcrk\u00e7e"},
        { "uk", "\u0423\u043A\u0440\u0430\u0457\u043D\u0441\u044C\u043A\u0430"},
        { "zh_CN", "\u4E2D\u6587"},
    };

    private static final String[] GENERIC = new String[] {
        "Generic JNDI Data Source|javax.naming.InitialContext|java:comp/env/jdbc/Test|sa",
        "Generic Firebird Server|org.firebirdsql.jdbc.FBDriver|jdbc:firebirdsql:localhost:c:/temp/firebird/test|sysdba",
        "Generic OneDollarDB|in.co.daffodil.db.jdbc.DaffodilDBDriver|jdbc:daffodilDB_embedded:school;path=C:/temp;create=true|sa",
        "Generic DB2|COM.ibm.db2.jdbc.net.DB2Driver|jdbc:db2://<host>/<db>|" ,
        "Generic Oracle|oracle.jdbc.driver.OracleDriver|jdbc:oracle:thin:@<host>:1521:<instance>|scott" ,
        "Generic MS SQL Server 2000|com.microsoft.jdbc.sqlserver.SQLServerDriver|jdbc:microsoft:sqlserver://localhost:1433;DatabaseName=sqlexpress|sa",
        "Generic MS SQL Server 2005|com.microsoft.sqlserver.jdbc.SQLServerDriver|jdbc:sqlserver://localhost;DatabaseName=test|sa",
        "Generic PostgreSQL|org.postgresql.Driver|jdbc:postgresql:<db>|" ,
        "Generic MySQL|com.mysql.jdbc.Driver|jdbc:mysql://<host>:<port>/<db>|" ,
        "Generic Derby (Server)|org.apache.derby.jdbc.ClientDriver|jdbc:derby://localhost:1527/test;create=true|sa",
        "Generic Derby (Embedded)|org.apache.derby.jdbc.EmbeddedDriver|jdbc:derby:test;create=true|sa",
        "Generic HSQLDB|org.hsqldb.jdbcDriver|jdbc:hsqldb:test;hsqldb.default_table_type=cached|sa" ,
        // this will be listed on top for new installations
        "Generic H2|org.h2.Driver|jdbc:h2:~/test|sa",
    };

/*
    String[] list = Locale.getISOLanguages();
    for(int i=0; i<list.length; i++) System.out.print(list[i] + " ");
    String lang = new java.util.Locale("hu").getDisplayLanguage(new java.util.Locale("hu"));
        java.util.Locale.CHINESE.getDisplayLanguage(
        java.util.Locale.CHINESE);
       for(int i=0; i<lang.length(); i++)
         System.out.println(Integer.toHexString(lang.charAt(i))+" ");
*/
    /**
     * Hungarian spec chars: &eacute;&#369;&aacute;&#337;&uacute;&ouml;&uuml;&oacute;&iacute;&Eacute;&Aacute;&#368;&#336;&Uacute;&Ouml;&Uuml;&Oacute;&Iacute;
     * Or use PropertiesToUTF8
     */

    // private URLClassLoader urlClassLoader;
    private String driverList;
    private static int ticker;
    private int port;
    private boolean allowOthers;
    private Set running = Collections.synchronizedSet(new HashSet());
    private boolean ssl;
    private HashMap connInfoMap = new HashMap();

    private static final long SESSION_TIMEOUT = 30 * 60 * 1000; // timeout is 30 min
    private long lastTimeoutCheck;
    private HashMap sessions = new HashMap();
    private HashSet languages = new HashSet();
    private String startDateTime;
    private ServerSocket serverSocket;
    private String url;
    private ShutdownHandler shutdownHandler;
    private Thread listenerThread;
    private boolean ifExists;
    private boolean allowScript;

    byte[] getFile(String file) throws IOException {
        trace("getFile <" + file + ">");
        byte[] data = Resources.get("/org/h2/server/web/res/" + file);
        if (data == null) {
            trace(" null");
        } else {
            trace(" size=" + data.length);
        }
        return data;
    }

    String getTextFile(String file) throws IOException {
        byte[] bytes = getFile(file);
        return new String(bytes);
    }

    synchronized void remove(WebThread t) {
        running.remove(t);
    }

    private String generateSessionId() {
        byte[] buff = RandomUtils.getSecureBytes(16);
        return ByteUtils.convertBytesToString(buff);
    }

    WebSession getSession(String sessionId) {
        long now = System.currentTimeMillis();
        if (lastTimeoutCheck + SESSION_TIMEOUT < now) {
            Object[] list = sessions.keySet().toArray();
            for (int i = 0; i < list.length; i++) {
                String id = (String) list[i];
                WebSession session = (WebSession) sessions.get(id);
                Long last = (Long) session.get("lastAccess");
                if (last != null && last.longValue() + SESSION_TIMEOUT < now) {
                    trace("timeout for " + id);
                    sessions.remove(id);
                }
            }
            lastTimeoutCheck = now;
        }
        WebSession session = (WebSession) sessions.get(sessionId);
        if (session != null) {
            session.lastAccess = System.currentTimeMillis();
        }
        return session;
    }

    WebSession createNewSession(String hostname) {
        String newId;
        do {
            newId = generateSessionId();
        } while(sessions.get(newId) != null);
        WebSession session = new WebSession(this);
        session.put("sessionId", newId);
        session.put("ip", hostname);
        session.put("language", DEFAULT_LANGUAGE);
        sessions.put(newId, session);
        // always read the english translation, to that untranslated text appears at least in english
        readTranslations(session, DEFAULT_LANGUAGE);
        return getSession(newId);
    }

    String getStartDateTime() {
        return startDateTime;
    }

    public void init(String[] args) throws Exception {
        // TODO web: support using a different properties file
        Properties prop = loadProperties();
        driverList = prop.getProperty("drivers");
        port = FileUtils.getIntProperty(prop, "webPort", Constants.DEFAULT_HTTP_PORT);
        ssl = FileUtils.getBooleanProperty(prop, "webSSL", Constants.DEFAULT_HTTP_SSL);
        allowOthers = FileUtils.getBooleanProperty(prop, "webAllowOthers", Constants.DEFAULT_HTTP_ALLOW_OTHERS);
        for (int i = 0; args != null && i < args.length; i++) {
            String a = args[i];
            if ("-webPort".equals(a)) {
                port = MathUtils.decodeInt(args[++i]);
            } else if ("-webSSL".equals(a)) {
                ssl = Boolean.valueOf(args[++i]).booleanValue();
            } else if ("-webAllowOthers".equals(a)) {
                allowOthers = Boolean.valueOf(args[++i]).booleanValue();
            } else if ("-webScript".equals(a)) {
                allowScript = Boolean.valueOf(args[++i]).booleanValue();
            } else if ("-baseDir".equals(a)) {
                String baseDir = args[++i];
                SysProperties.setBaseDir(baseDir);
            } else if ("-ifExists".equals(a)) {
                ifExists = Boolean.valueOf(args[++i]).booleanValue();
            }
        }
//            if(driverList != null) {
//                try {
//                    String[] drivers = StringUtils.arraySplit(driverList, ',', false);
//                    URL[] urls = new URL[drivers.length];
//                    for(int i=0; i<drivers.length; i++) {
//                        urls[i] = new URL(drivers[i]);
//                    }
//                    urlClassLoader = URLClassLoader.newInstance(urls);
//                } catch (MalformedURLException e) {
//                    TraceSystem.traceThrowable(e);
//                }
//            }
        SimpleDateFormat format = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", new Locale("en", ""));
        synchronized (format) {
            format.setTimeZone(TimeZone.getTimeZone("GMT"));
            startDateTime = format.format(new Date());
        }
        trace(startDateTime);
        for (int i = 0; i < LANGUAGES.length; i++) {
            languages.add(LANGUAGES[i][0]);
        }
        url = (ssl ? "https" : "http") + "://" + NetUtils.getLocalAddress() + ":" + port;
    }

    public String getURL() {
        return url;
    }

    public void start() throws SQLException {
        serverSocket = NetUtils.createServerSocket(port, ssl);
    }

    public void listen() {
        this.listenerThread = Thread.currentThread();
        try {
            while (serverSocket != null) {
                Socket s = serverSocket.accept();
                WebThread c = new WebThread(s, this);
                running.add(c);
                c.start();
            }
        } catch (Exception e) {
            trace(e.toString());
        }
    }

    public boolean isRunning() {
        if (serverSocket == null) {
            return false;
        }
        try {
            Socket s = NetUtils.createLoopbackSocket(port, ssl);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void stop() {
        try {
            serverSocket.close();
        } catch (IOException e) {
            // TODO log exception
        }
        serverSocket = null;
        if (listenerThread != null) {
            try {
                listenerThread.join(1000);
            } catch (InterruptedException e) {
                TraceSystem.traceThrowable(e);
            }
        }
        // TODO server: using a boolean 'now' argument? a timeout?
        ArrayList list = new ArrayList(sessions.values());
        for (int i = 0; i < list.size(); i++) {
            WebSession session = (WebSession) list.get(i);
            Statement stat = session.executingStatement;
            if (stat != null) {
                try {
                    stat.cancel();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        list = new ArrayList(running);
        for (int i = 0; i < list.size(); i++) {
            WebThread c = (WebThread) list.get(i);
            try {
                c.stopNow();
                c.join(100);
            } catch (Exception e) {
                // TODO log exception
                e.printStackTrace();
            }
        }
    }

    void trace(String s) {
        // System.out.println(s);
    }

    public void traceError(Exception e) {
        e.printStackTrace();
    }

    public boolean supportsLanguage(String language) {
        return languages.contains(language);
    }

    public void readTranslations(WebSession session, String language) {
        Properties text = new Properties();
        try {
            trace("translation: "+language);
            byte[] trans = getFile("_text_"+language+".properties");
            trace("  "+new String(trans));
            text.load(new ByteArrayInputStream(trans));
        } catch (IOException e) {
            TraceSystem.traceThrowable(e);
        }
        session.put("text", new HashMap(text));
    }

    public String[][] getLanguageArray() {
        return LANGUAGES;
    }

    public ArrayList getSessions() {
        ArrayList list = new ArrayList(sessions.values());
        for (int i = 0; i < list.size(); i++) {
            WebSession s = (WebSession) list.get(i);
            list.set(i, s.getInfo());
        }
        return list;
    }

    public String getType() {
        return "Web";
    }

    public String getName() {
        return "H2 Console Server";
    }

    void setAllowOthers(boolean b) {
        allowOthers = b;
    }

    public boolean getAllowOthers() {
        return allowOthers;
    }

    void setSSL(boolean b) {
        ssl = b;
    }

    void setPort(int port) {
        this.port = port;
    }

    boolean getSSL() {
        return ssl;
    }

    int getPort() {
        return port;
    }

    ConnectionInfo getSetting(String name) {
        return (ConnectionInfo) connInfoMap.get(name);
    }

    void updateSetting(ConnectionInfo info) {
        connInfoMap.put(info.name, info);
        info.lastAccess = ticker++;
    }

    void removeSetting(String name) {
        connInfoMap.remove(name);
    }

    private String getPropertiesFileName() {
        // store the properties in the user directory
        return FileUtils.getFileInUserHome(Constants.SERVER_PROPERTIES_FILE);
    }

    Properties loadProperties() {
        String fileName = getPropertiesFileName();
        try {
            return FileUtils.loadProperties(fileName);
        } catch (IOException e) {
            // TODO log exception
            return new Properties();
        }
    }

    String[] getSettingNames() {
        ArrayList list = getSettings();
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = ((ConnectionInfo) list.get(i)).name;
        }
        return names;
    }

    synchronized ArrayList getSettings() {
        ArrayList settings = new ArrayList();
        if (connInfoMap.size() == 0) {
            Properties prop = loadProperties();
            if (prop.size() == 0) {
                for (int i = 0; i < GENERIC.length; i++) {
                    ConnectionInfo info = new ConnectionInfo(GENERIC[i]);
                    settings.add(info);
                    updateSetting(info);
                }
            } else {
                for (int i = 0;; i++) {
                    String data = prop.getProperty(String.valueOf(i));
                    if (data == null) {
                        break;
                    }
                    ConnectionInfo info = new ConnectionInfo(data);
                    settings.add(info);
                    updateSetting(info);
                }
            }
        } else {
            settings.addAll(connInfoMap.values());
        }
        sortConnectionInfo(settings);
        return settings;
    }

    void sortConnectionInfo(ArrayList list) {
        for (int i = 1, j; i < list.size(); i++) {
            ConnectionInfo t = (ConnectionInfo) list.get(i);
            for (j = i - 1; j >= 0 && (((ConnectionInfo) list.get(j)).lastAccess < t.lastAccess); j--) {
                list.set(j + 1, list.get(j));
            }
            list.set(j + 1, t);
        }
    }

    synchronized void saveSettings() {
        try {
            Properties prop = new SortedProperties();
            if (driverList != null) {
                prop.setProperty("drivers", driverList);
            }
            prop.setProperty("webPort", String.valueOf(port));
            prop.setProperty("webAllowOthers", String.valueOf(allowOthers));
            prop.setProperty("webSSL", String.valueOf(ssl));
            ArrayList settings = getSettings();
            int len = settings.size();
            for (int i = 0; i < len; i++) {
                ConnectionInfo info = (ConnectionInfo) settings.get(i);
                if (info != null) {
                    prop.setProperty(String.valueOf(len - i - 1), info.getString());
                }
            }
            OutputStream out = FileUtils.openFileOutputStream(getPropertiesFileName(), false);
            prop.store(out, Constants.SERVER_PROPERTIES_TITLE);
            out.close();
        } catch (Exception e) {
            TraceSystem.traceThrowable(e);
        }
    }

    Connection getConnection(String driver, String url, String user, String password, DatabaseEventListener listener) throws Exception {
        driver = driver.trim();
        url = url.trim();
        org.h2.Driver.load();
        Properties p = new Properties();
        p.setProperty("user", user.trim());
        p.setProperty("password", password.trim());
        if (url.startsWith("jdbc:h2:")) {
            if (ifExists) {
                url += ";IFEXISTS=TRUE";
            }
            p.put("DATABASE_EVENT_LISTENER_OBJECT", listener);
            // PostgreSQL would throw a NullPointerException
            // if it is loaded before the H2 driver
            // because it can't deal with non-String objects in the connection Properties
            return org.h2.Driver.load().connect(url, p);
        }
//            try {
//                Driver dr = (Driver) urlClassLoader.loadClass(driver).newInstance();
//                return dr.connect(url, p);
//            } catch(ClassNotFoundException e2) {
//                throw e2;
//            }
        return JdbcUtils.getConnection(driver, url, p);
    }

    void shutdown() {
        if (shutdownHandler != null) {
            shutdownHandler.shutdown();
        }
    }

    public void setShutdownHandler(ShutdownHandler shutdownHandler) {
        this.shutdownHandler = shutdownHandler;
    }

    public boolean getAllowScript() {
        return allowScript;
    }

}
