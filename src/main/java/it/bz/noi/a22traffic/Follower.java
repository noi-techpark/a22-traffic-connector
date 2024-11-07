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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * A22 traffic API connector: implements the "follow" operation.
 */
public class Follower {

    private static final boolean DEBUG = true;

    public static void fetchNew(Connector conn, String jdbc_url) throws IOException, ClassNotFoundException, SQLException {

        int i;
        PreparedStatement pst;
        ResultSet rs;

        // ---------------------------------------------------------------------
        // connect to the DB
        Connection db;
        Class.forName("org.postgresql.Driver");
        db = DriverManager.getConnection(jdbc_url);
        db.setAutoCommit(false);

        // ---------------------------------------------------------------------
        // get the sensors
        ArrayList<HashMap<String, String>> sensors = conn.getTrafficSensors();
        System.out.println("follow mode: number of sensors: " + sensors.size());

        // ---------------------------------------------------------------------
        // for each sensor
        //  - perform an insert on conflict into table a22.a22_station to store new sensors,
        //    if there are any, or to update their name or description,
        //  - idem for table a22.a22_station_detail, where the raw metadata is stored
        pst = db.prepareStatement("insert into a22.a22_station (code, name, geo) values (?, ?, ?) on conflict (code) " +
                " do update set name = ?, geo = ?");
        for (i = 0; i < sensors.size(); i++) {
            pst.setString(1, sensors.get(i).get("stationcode"));
            pst.setString(2, sensors.get(i).get("name"));
            pst.setString(3, sensors.get(i).get("pointprojection"));
            pst.setString(4, sensors.get(i).get("name"));
            pst.setString(5, sensors.get(i).get("pointprojection"));
            pst.execute();
        }
        pst.close();
        pst = db.prepareStatement("insert into a22.a22_station_detail (code, data) values (?, ?) on conflict (code) " +
                " do update set data = ?");
        for (i = 0; i < sensors.size(); i++) {
            pst.setString(1, sensors.get(i).get("stationcode"));
            pst.setString(2, sensors.get(i).get("raw_metadata"));
            pst.setString(3, sensors.get(i).get("raw_metadata"));
            pst.execute();
        }
        pst.close();
        db.commit();

        System.out.println("follow mode: sensor data and metadata updated");

        // ---------------------------------------------------------------------
        // reshape sensors, so we get a list of sensors associated to each coilid
        HashMap<String, ArrayList<String>> coils = new HashMap<>();
        for (i = 0; i < sensors.size(); i++) {
            // stationcode = A22:coilid:sensorid
            String split[] = sensors.get(i).get("stationcode").split(":");
            if (split.length != 3) {
                System.out.println("skipping wrong format station code: " + sensors.get(i).get("stationcode"));
                continue;
            }
            String coilid = split[1];
            if (coils.get(coilid) == null) {
                coils.put(coilid, new ArrayList<>());
            }
            coils.get(coilid).add(sensors.get(i).get("stationcode"));
        }
        
        // ---------------------------------------------------------------------
        // add ghost sensors to the list (exclude unghosted sensors)
        pst = db.prepareStatement("select code from a22.a22_ghost_station except select code from a22.a22_station");
        rs = pst.executeQuery();
        int ghost_sensor_cnt = 0;
        while (rs.next()) {
            String s = rs.getString(1);
            if (s == null) {
                continue;
            }
             // stationcode = A22:coilid:sensorid
            String split[] = s.split(":");
            if (split.length != 3) {
                System.out.println("skipping wrong format station code: " + s);
                continue;
            }
            String coilid = split[1];
            if (coils.get(coilid) == null) {
                coils.put(coilid, new ArrayList<>());
            }
            coils.get(coilid).add(s);
            ghost_sensor_cnt++;
        }
        pst.close();
        db.commit();
        System.out.println("follow mode: ghost sensor count: " + ghost_sensor_cnt);
        
        // ---------------------------------------------------------------------
        // for each coilid, get the max(timestamp) among its sensors (going back up to one week)
        // also create a fast sensor lookup list 
        HashMap<String, Integer> coils_ts = new HashMap<>();
        HashMap<String, Integer> sensor_known = new HashMap<>();
        long cap = Instant.now().getEpochSecond() - 7 * 24 * 60 * 60;
        System.out.println("follow mode: getting max(timestamp) for each sensor capped at " + cap);
        pst = db.prepareStatement("select coalesce(max(timestamp)," + cap + ") from a22.a22_traffic where stationcode = ? and timestamp > " + cap);
        for (String coilid : coils.keySet()) {
            if (DEBUG) {
                System.out.println("coil id: " + coilid);
                System.out.print("  +- ");
            }
            int max = 0;
            for (String c : coils.get(coilid)) {
                if (DEBUG) {
                    System.out.print(c + " ");
                }
                pst.setString(1, c);
                rs = pst.executeQuery();
                rs.next();
                int t = rs.getInt(1);
                if (t > max) {
                    max = t;
                }
                sensor_known.put(c, 1);
            }
            if (max == 0) {
                max = (int)cap; // uhm year 2038 problem... but the db has an int field anyway
            }
            max = max + 1;
            if (DEBUG) {
                System.out.print(" -> max ts = " + max);
                System.out.println();
            } else {
                System.out.print(".");
                System.out.flush();
            }
            coils_ts.put(coilid, max);
        }
        pst.close();
        db.commit();
        if (!DEBUG) {
            System.out.println();
        }

        // ---------------------------------------------------------------------
        // perform getVehicles() operation, insert new events, look for ghost sensors
        long t0 = System.currentTimeMillis();

        ArrayList<HashMap<String, String>> res;
        HashMap<String, Integer> detected_ghosts = new HashMap<>();
        
        System.out.println("follow mode: getting events:");

        res = conn.getVehicles(0, 0, Instant.now().getEpochSecond(), sensors, coils_ts);

        long t1 = System.currentTimeMillis();

        pst = db.prepareStatement(
                "insert into a22.a22_traffic "
                + "(stationcode, timestamp, distance, headway, length, axles, against_traffic, class, speed, direction, country, license_plate_initials) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        for (i = 0; i < res.size(); i++) {
            String s = res.get(i).get("stationcode");
            if (!sensor_known.containsKey(s)) {
                detected_ghosts.put(s, 1);
            }
            pst.setString(1, res.get(i).get("stationcode"));
            pst.setInt(2, Integer.parseInt(res.get(i).get("timestamp")));
            pst.setDouble(3, Double.parseDouble(res.get(i).get("distance")));
            pst.setDouble(4, Double.parseDouble(res.get(i).get("headway")));
            pst.setDouble(5, Double.parseDouble(res.get(i).get("length")));
            pst.setInt(6, Integer.parseInt(res.get(i).get("axles")));
            pst.setBoolean(7, Boolean.parseBoolean(res.get(i).get("against_traffic")));
            pst.setInt(8, Integer.parseInt(res.get(i).get("class")));
            pst.setDouble(9, Double.parseDouble(res.get(i).get("speed")));
            pst.setInt(10, Integer.parseInt(res.get(i).get("direction")));
            try {
                String country = res.get(i).get("country");
                pst.setInt(11, Integer.parseInt(country));
            } catch (NullPointerException | NumberFormatException e) {
                pst.setObject(11, null, Types.INTEGER);
            }
            pst.setString(12, "".equals(res.get(i).get("license_plate_initials")) ? null: res.get(i).get("license_plate_initials"));
            pst.execute();
        }
        pst.close();
        db.commit();

        long t2 = System.currentTimeMillis();

        pst = db.prepareStatement("insert into a22.a22_ghost_station (code) values (?)");
        for (String s : detected_ghosts.keySet()) {
            pst.setString(1, s);
            pst.execute();
        }
        pst.close();
        db.commit();
        
        System.out.println("follow mode: " + res.size() + " records (retrieve " + (t1 - t0) + " ms, store " + (t2 - t1) + " ms), new ghost sensors detected: " + detected_ghosts.size());

        // ---------------------------------------------------------------------
        // disconnect from Postgres
        db.close();

    }

}
