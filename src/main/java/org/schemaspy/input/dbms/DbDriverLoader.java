/*
 * Copyright (C) 2004 - 2011 John Currier
 * Copyright (C) 2016, 2017 Rafal Kasa
 * Copyright (C) 2017 Wojciech Kasa
 * Copyright (C) 2017, 2018 Nils Petzaell
 * Copyright (C) 2017 Daniel Watt
 *
 * This file is a part of the SchemaSpy project (http://schemaspy.org).
 *
 * SchemaSpy is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * SchemaSpy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.schemaspy.input.dbms;

import org.schemaspy.connection.PreferencesConnection;
import org.schemaspy.connection.WithPassword;
import org.schemaspy.connection.WithUser;
import org.schemaspy.input.dbms.driverpath.*;
import org.schemaspy.input.dbms.drivers.LoadAdditionalJarsForDriver;
import org.schemaspy.input.dbms.exceptions.ConnectionFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author John Currier
 * @author Rafal Kasa
 * @author Wojciech Kasa
 * @author Nils Petzaell
 * @author Daniel Watt
 */
public class DbDriverLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static Map<String, Driver> driverCache = new HashMap<>();
    private final ConnectionConfig connectionConfig;
    private final ConnectionURLBuilder urlBuilder;
    private final Properties properties;
    private final String[] driverClass;
    private Driverpath driverPath;

    public DbDriverLoader(final ConnectionConfig connectionConfig) {
        this(connectionConfig, new ConnectionURLBuilder(connectionConfig));
    }

    public DbDriverLoader(final ConnectionConfig connectionConfig, final ConnectionURLBuilder urlBuilder) {
        this(connectionConfig, urlBuilder, connectionConfig.getDatabaseTypeProperties());
    }

    public DbDriverLoader(
        final ConnectionConfig connectionConfig,
        final ConnectionURLBuilder urlBuilder,
        final Properties properties
    ) {
        this(
            connectionConfig,
            urlBuilder,
            properties,
            properties.getProperty("driver").split(","),
            new DpConnectionConfig(connectionConfig)
        );
    }

    public DbDriverLoader(
        final ConnectionConfig connectionConfig,
        final ConnectionURLBuilder urlBuilder,
        final Properties properties,
        final String[] driverClass,
        final Driverpath driverPath
    ) {
        this.connectionConfig = connectionConfig;
        this.urlBuilder = urlBuilder;
        this.properties = properties;
        this.driverClass = driverClass;
        this.driverPath = driverPath;
    }

    public Connection getConnection() throws IOException {
        final Driverpath dp = new DpFallback(
            driverPath,
            new DpFallback(
                new DpProperties(properties),
                new DpNull()
            )
        );
        return getConnection(urlBuilder.build(), driverClass, dp.value());
    }

    protected Connection getConnection(
        String connectionURL,
        String[] driverClasses,
        String driverPath
    ) throws IOException {
        final Properties connectionProperties = new WithPassword(
            connectionConfig.getPassword(),
            new WithUser(
                connectionConfig.getUser(),
                new PreferencesConnection(connectionConfig.getConnectionProperties())
            )
        ).properties();

        Connection connection;
        try {
            Driver driver = getDriver(driverClasses, driverPath);
            connection = driver.connect(connectionURL, connectionProperties);
            if (connection == null) {
                throw new ConnectionFailure("Cannot connect to '" + connectionURL +"' with driver '" + toList(driverClasses) + "'");
            }
        } catch (UnsatisfiedLinkError badPath) {
            throw new ConnectionFailure("Error with native library occurred while trying to use driver '"+ toList(driverClasses)+"'",badPath);
        } catch (Exception exc) {
            throw new ConnectionFailure("Failed to connect to database URL [" + connectionURL + "]", exc);
        }
        return connection;
    }

    private String toList(String[] array) {
        if (array.length == 1) {
            return array[0];
        }
        return Stream.of(array).collect(Collectors.joining(","));
    }

    /**
     * Returns an instance of {@link Driver} specified by <code>driverClass</code>
     * loaded from <code>driverPath</code>.
     *
     * @param driverClasses
     * @param driverPath
     * @return
     */
    protected synchronized Driver getDriver(final String []driverClasses, final String driverPath) {
        Driver driver;
        for (String driverClass: driverClasses) {
            driver = driverCache.get(driverClass + "|" + driverPath);
            if (Objects.nonNull(driver)) {
                return driver;
            }
        }
        Set<URI> classpath = new GetExistingUrls().getExistingUrls(driverPath);
        if (classpath.isEmpty()) {
            URL url = getClass().getResource(driverPath);
            if (url != null) {
                classpath = new GetExistingUrls().getExistingUrls(url.getPath());
            }
        }

        //If this option is true additional jars used by JDBC Driver will be loaded to the classpath
        if (connectionConfig.withLoadSiblings()) {
            classpath.addAll(new LoadAdditionalJarsForDriver(driverPath).value());
        }


        ClassLoader loader = getDriverClassLoader(classpath);
        Class<Driver> driverClass = getDriverClass(driverClasses, loader);

        if (Objects.isNull(driverClass)) {
            throw new ConnectionFailure(createMessage(driverClasses, driverPath, classpath));
        }

        try {
            driver = driverClass.newInstance();
            if (Objects.nonNull(driver)) {
                driverCache.put(driverClass.getName() + "|" + driverPath, driver);
            }
            // have to use deprecated method or we won't see messages generated by older drivers
            // @see DriverManager.setLogStream(PrintStream)
            //TODO implement PrintStream to Logger bridge.
            // setLogStream should only be called once maybe in SpringConfig or Main?
        } catch (Exception exc) {
            throw new ConnectionFailure(createMessage(driverClasses, driverPath, classpath), exc);
        }

        return driver;
    }

    private Class<Driver> getDriverClass(String[] driverClasses, ClassLoader loader) {
        Class<Driver> driverClass = null;
        for(String potentialDriverClass : driverClasses) {
            try {
                driverClass = (Class<Driver>) Class.forName(potentialDriverClass, true, loader);
            } catch (ClassNotFoundException e) {
                LOGGER.debug("Unable to find driverClass '{}'", potentialDriverClass);
            }
            if (Objects.nonNull(driverClass)) {
                return driverClass;
            }
        }
        return null;
    }

    private String createMessage(String []driverClass, String driverPath, Set<URI> classpath) {
        StringBuilder sb = new StringBuilder()
                .append("Failed to create any of '")
                .append(Arrays.stream(driverClass).collect(Collectors.joining(", ")))
                .append("' driver from driverPath '")
                .append(driverPath)
                .append("' with sibling jars ")
                .append((connectionConfig.withLoadSiblings() ? "yes": "no"))
                .append(".")
                .append(System.lineSeparator())
                .append("Resulting in classpath:");
        if (classpath.isEmpty()) {
            sb.append(" empty").append(System.lineSeparator());
        } else {
            sb.append(System.lineSeparator());
            for (URI uri : classpath) {
                sb.append("\t").append(uri.toString()).append(System.lineSeparator());
            }
        }
        List<String> missingPaths = getMissingPaths(driverPath);
        if (!missingPaths.isEmpty()) {
            sb.append("There were missing paths in driverPath:").append(System.lineSeparator());
            for (String path : missingPaths) {
                sb.append("\t").append(path).append(System.lineSeparator());
            }
            sb
                    .append("Use commandline option '-dp' to specify driver location.")
                    .append(System.lineSeparator())
                    .append("If you need to load sibling jars used '-loadjars'");
        }
        return sb.toString();
    }

    /**
     * Returns a {@link ClassLoader class loader} to use for resolving {@link Driver}s.
     *
     * @param classpath
     * @return
     */
    private ClassLoader getDriverClassLoader(Set<URI> classpath) {
        ClassLoader loader;

        // if a classpath has been specified then use it to find the driver,
        // otherwise use whatever was used to load this class.
        // thanks to Bruno Leonardo Gonalves for this implementation that he
        // used to resolve issues when running under Maven
        if (!classpath.isEmpty()) {
            URL[] urls = classpath.stream().map(uri -> {
                try {
                    return uri.toURL();
                } catch (MalformedURLException e) {
                    return null;
                }
            }).filter(Objects::nonNull).collect(Collectors.toList()).toArray(new URL[classpath.size()]);
            loader = new URLClassLoader(urls);
        } else {
            loader = getClass().getClassLoader();
        }

        return loader;
    }

    /**
     * Returns a list of {@link File}s in <code>path</code> that do not exist.
     * The intent is to aid in diagnosing invalid paths.
     *
     * @param path
     * @return
     */
    private List<String> getMissingPaths(String path) {
        List<String> missingFiles = new ArrayList<>();

        String[] pieces = path.split(File.pathSeparator);
        for (String piece : pieces) {
            if (!new File(piece).exists())
                missingFiles.add(piece);
        }

        return missingFiles;
    }
}
