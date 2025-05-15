// SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
//
// SPDX-License-Identifier: AGPL-3.0-or-later

/*
    A22 traffic API connector.

    Retrieve A22 traffic data and store it into a PostgreSQL database.

    (C) 2019-2022 NOI Techpark Südtirol / Alto Adige
    (C) 2018 IDM Suedtirol - Alto Adige

    Author: Chris Mair - chris@1006.org  
 */
package it.bz.noi.a22traffic;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * A22 traffic API connector: CLI.
 *
 * Usage: java -jar A22TrafficConnector.jar { month <year> <month> | interval <start_ts> <end_ts> | follow }
 *
 * The following system properties must be set: JDBC_ENDPOINT, JDBC_DBNAME, JDBC_USERNAME, JDBC_PASSWORD.
 */
public class Main {

    /**
     * Main entry point.
     *
     * @param args
     *
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {

        System.out.println("A22TrafficConnector (20221124) invoked at " + ZonedDateTime.now());

        // ---------------------------------------------------------------------
        // parse and validate arguments
        if (args.length == 0) {
            System.err.println("ERROR: expected arguments: { month <year> <month> | interval <start_ts> <end_ts> | follow }");
            return;
        }
        long epoch_start = 0;
        long epoch_end = 0;
        boolean follow = false;

        switch (args[0]) {
            case "month":
                int year,
                 month;
                try {
                    year = Integer.parseInt(args[1]);
                    month = Integer.parseInt(args[2]);
                } catch (NumberFormatException ex) {
                    year = 0;
                    month = 0;
                }
                if (year < 1990 || year > 2100 || month < 1 || month > 12) {
                    System.err.println("ERROR: missing or invalid arguments after 'month'");
                    return;
                }
                System.out.println("args: month " + year + " " + month);
                Calendar cal = Calendar.getInstance();
                cal.setTimeZone(TimeZone.getTimeZone("UTC"));
                cal.set(year, month - 1, 1, 0, 0, 0);
                epoch_start = cal.getTimeInMillis() / 1000;
                System.out.println("start: " + epoch_start);
                if (++month == 13) {
                    year++;
                    month = 1;
                }
                cal.set(year, month - 1, 1, 0, 0, 0);
                epoch_end = cal.getTimeInMillis() / 1000;
                System.out.println("end:   " + epoch_end);
                break;
            case "interval":
                long start,
                 end;
                try {
                    start = Long.parseLong(args[1]);
                    end = Long.parseLong(args[2]);
                } catch (NumberFormatException ex) {
                    start = 0;
                    end = 0;
                }
                if (start < 631152000L /* 1990 */ || end > 4102444800L /* 2100 */ || start > end) {
                    System.err.println("ERROR: missing or invalid arguments after 'interval'");
                    return;
                }
                System.out.println("args: interval " + start + " " + end);
                epoch_start = start;
                epoch_end = end;
                break;
            case "follow":
                follow = true;
                break;
            default:
                System.err.println("ERROR: missing or invalid arguments");
                return;
        }

        // ---------------------------------------------------------------------
        // read JDBC credentials
        String jdbc_url = System.getenv("JDBC_URL");
        if (jdbc_url == null) {
            System.err.println("ERROR: missing system property JDBC_URL");
            return;
        }

        // ---------------------------------------------------------------------
        // connect to the DB to get the A22 web service credentials
        String a22_url = null;
        String a22_auth_json = null;
        Connection db;
        try {
            Class.forName("org.postgresql.Driver");
            db = DriverManager.getConnection(jdbc_url);
        } catch (ClassNotFoundException | SQLException e) {
            e.printStackTrace();
            System.out.println(jdbc_url);
            System.err.println("ERROR: failed to connect to the database: " + e.getMessage());
            return;
        }
        try {
            Statement stm = db.createStatement();
            ResultSet rs = stm.executeQuery("select url, username, password from a22.a22_webservice where id = 1");
            if (rs.next()) {
                a22_url = rs.getString(1);
                a22_auth_json = "{\"request\":{\"username\":\"" + rs.getString(2) + "\",\"password\":\"" + rs.getString(3) + "\"}}";
            }
            rs.close();
            stm.close();
            db.close();
        } catch (SQLException e) {
            System.err.println("ERROR: failed to retrieve A22 web service credentials: " + e.getMessage());
            return;
        }
        if (a22_url == null || a22_auth_json == null) {
            System.err.println("ERROR: A22 web service credentials not found in database");
            return;

        }

        // ---------------------------------------------------------------------
        // start work according to the selected mode of operation
        if (follow) {

            // enter an infinite loop, fetching new data (argument "follow")
            long iteration = 0;
            while (true) {

                try {
                    iteration++;
                    
                    System.out.println(ZonedDateTime.now() + " A22TrafficConnector follow mode: woke up (iteration " + iteration + ")");

                    // connect to A22 web service
                    Connector conn = new Connector(a22_url, a22_auth_json);

                    Follower.fetchNew(conn, jdbc_url);

                    // disconnect from A22 service
                    conn.close();

                } catch (IOException | ClassNotFoundException | SQLException | RuntimeException e) {
                    System.out.println("something went wrong (" + e.getMessage() + ") - will go to sleep anyway");
                    // intentionally non-fatal
                }

                System.out.println(ZonedDateTime.now() + " A22TrafficConnector follow mode: going to sleep");
                
                try {
                    Thread.sleep(30000); // sleep 30 seconds
                } catch (InterruptedException e) {
                }
                
            }

        } else {

            // perform the requested bulk operation (arguments "month" or "interval")
            // in multiple threads and exit when ready
            // connect to A22 web service
            Connector conn = new Connector(a22_url, a22_auth_json);

            int thread_count = 8;
            int i;
            long delta = (epoch_end - epoch_start) / thread_count;
            long first, last;

            // prepare an hashmap for each thread to yield min and max timestamps
            List<Map<String, long[]>> threadResults = new ArrayList<>();
            for (int j = 0; j < thread_count; j++) {
                threadResults.add(new HashMap<>());
            }

            Runnable bulkloader[] = new Runnable[thread_count];
            Thread thread[] = new Thread[thread_count];
            for (i = 0; i < thread_count; i++) {
                first = epoch_start + i * delta;
                if (i < thread_count - 1) {
                    last = epoch_start + i * delta + delta - 1;
                } else {
                    last = epoch_end;
                }
                bulkloader[i] = new BulkLoader(i, jdbc_url, conn, first, last, threadResults.get(i));
                thread[i] = new Thread(bulkloader[i]);
                thread[i].start();
            }

            for (i = 0; i < thread_count; i++) {
                thread[i].join();
            }

            // disconnect from A22 service
            conn.close();

            // flush min and max timestamps
            Stations.updateStationTimestampsBulk(jdbc_url, threadResults);
        }

        System.out.println("A22TrafficConnector exited at " + ZonedDateTime.now());

    }

}
