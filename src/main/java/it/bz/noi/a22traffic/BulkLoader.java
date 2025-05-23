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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * A22 traffic API connector: runnable for bulk operations (arguments "month" or "interval").
 */
public class BulkLoader implements Runnable {

    private final int thread_num;
    private final String jdbc_url;
    private final Connector conn;
    private final long epoch_start;
    private final long epoch_end;
    private final Map<String, long[]> load_result;

    public BulkLoader(int thread_num, String jdbc_url, Connector conn, 
        long epoch_start, long epoch_end, Map<String, long[]> load_result) {
        this.thread_num = thread_num;
        this.jdbc_url = jdbc_url;
        this.conn = conn;
        this.epoch_start = epoch_start;
        this.epoch_end = epoch_end;
        this.load_result = load_result;
    }

    @Override
    public void run() {

        try {

            System.out.println("th" + thread_num + " thread started at " + ZonedDateTime.now());

            // ---------------------------------------------------------------------
            // connect to Postgres
            Class.forName("org.postgresql.Driver");
            Connection db = DriverManager.getConnection(jdbc_url);

            // ---------------------------------------------------------------------
            // get the sensors
            ArrayList<HashMap<String, String>> sensors = conn.getTrafficSensors();
            System.out.println("th" + thread_num + " number of sensors: " + sensors.size());

            Map<String, String> countries = conn.getCountries();

            // ---------------------------------------------------------------------
            // perform getVehicles() operation in batches of 1000 seconds each
            long batch = epoch_start;

            ArrayList<HashMap<String, String>> res;

            while (batch < epoch_end - 1) {
                long fr = batch;
                long to = Math.min(epoch_end - 1, batch + 999);

                System.out.println("th" + thread_num + " time:     " + ZonedDateTime.now());
                System.out.println("th" + thread_num + " interval: " + fr + " .. " + to);

                long t0 = System.currentTimeMillis();

                res = conn.getVehicles(thread_num, fr, to, sensors, null);

                long t1 = System.currentTimeMillis();

                db.setAutoCommit(false);
                PreparedStatement ins = db.prepareStatement(
                        "insert into a22.a22_traffic "
                        + "(stationcode, timestamp, distance, headway, length, axles, against_traffic, class, speed, direction, country, license_plate_initials) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                for (int i = 0; i < res.size(); i++) {
                    // min and max timestamp handling
                    String stationcode = res.get(i).get("stationcode");
                    long ts = Long.parseLong(res.get(i).get("timestamp"));

                    long[] bounds = this.load_result.getOrDefault(stationcode, new long[] { Long.MAX_VALUE, Long.MIN_VALUE });
                    bounds[0] = Math.min(bounds[0], ts);  // min timestamp
                    bounds[1] = Math.max(bounds[1], ts);  // max timestamp
                    this.load_result.put(stationcode, bounds);
                    
                    ins.setString(1, stationcode);
                    ins.setInt(2, Integer.parseInt(res.get(i).get("timestamp")));
                    ins.setDouble(3, Double.parseDouble(res.get(i).get("distance")));
                    ins.setDouble(4, Double.parseDouble(res.get(i).get("headway")));
                    ins.setDouble(5, Double.parseDouble(res.get(i).get("length")));
                    ins.setInt(6, Integer.parseInt(res.get(i).get("axles")));
                    ins.setBoolean(7, Boolean.parseBoolean(res.get(i).get("against_traffic")));
                    ins.setInt(8, Integer.parseInt(res.get(i).get("class")));
                    ins.setDouble(9, Double.parseDouble(res.get(i).get("speed")));
                    ins.setInt(10, Integer.parseInt(res.get(i).get("direction")));
                    ins.setString(11, countries.get(res.get(i).get("country")));
                    ins.setString(12, "".equals(res.get(i).get("license_plate_initials")) ? null: res.get(i).get("license_plate_initials"));
                    ins.addBatch();
                }
                ins.executeBatch();
                ins.close();
                db.commit();

                long t2 = System.currentTimeMillis();

                System.out.println("th" + thread_num + " " + res.size() + " records (retrieve " + (t1 - t0) + " ms, store " + (t2 - t1) + " ms)");

                batch += 1000;

            }

            // ---------------------------------------------------------------------
            // disconnect from Postgres
            db.close();

        } catch (IOException | ClassNotFoundException | NumberFormatException | SQLException e) {
            System.out.println("th" + thread_num + " RUNTIME EXCEPTION AT " + ZonedDateTime.now());
            System.out.println("th" + thread_num + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("th" + thread_num + " thread ended at " + ZonedDateTime.now());

    }

}
