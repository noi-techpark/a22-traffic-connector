package it.bz.noi.a22traffic;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Stations {

    public static void updateStationTimestampsBulk(String jdbc_url, List<Map<String, long[]>> threadResults) {
        // Step 1: Merge all thread maps into one global map
        Map<String, long[]> globalBounds = new HashMap<>();

        for (Map<String, long[]> result : threadResults) {
            for (Map.Entry<String, long[]> entry : result.entrySet()) {
                String code = entry.getKey();
                long[] threadBounds = entry.getValue();

                long[] current = globalBounds.getOrDefault(code, new long[] { Long.MAX_VALUE, Long.MIN_VALUE });
                current[0] = Math.min(current[0], threadBounds[0]); // min timestamp
                current[1] = Math.max(current[1], threadBounds[1]); // max timestamp
                globalBounds.put(code, current);
            }
        }

        // Step 2: Write the results to DB with guards
        updateStationTimestamps(jdbc_url, globalBounds);
    }

    public static void updateStationTimestamps(String jdbc_url, Map<String, long[]> timestamps) {
        // Write the results to DB with guards
        try {
            Class.forName("org.postgresql.Driver");
            Connection db = DriverManager.getConnection(jdbc_url);
            db.setAutoCommit(false);

            PreparedStatement upd = db.prepareStatement(
                "UPDATE a22.a22_station SET " +
                "min_timestamp = CASE WHEN min_timestamp > ? OR min_timestamp IS NULL THEN ? ELSE min_timestamp END, " +
                "max_timestamp = CASE WHEN max_timestamp < ? OR max_timestamp IS NULL THEN ? ELSE max_timestamp END " +
                "WHERE code = ?"
            );

            for (Map.Entry<String, long[]> entry : timestamps.entrySet()) {
                String stationcode = entry.getKey();
                long min = entry.getValue()[0];
                long max = entry.getValue()[1];

                upd.setLong(1, min);  // For condition check
                upd.setLong(2, min);  // New min value
                upd.setLong(3, max);  // For condition check
                upd.setLong(4, max);  // New max value
                upd.setString(5, stationcode);

                upd.addBatch();
            }

            upd.executeBatch();
            upd.close();
            db.commit();
            db.close();

            System.out.println("Station timestamp updates completed: " + timestamps.size() + " stations.");
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to update station timestamps", e);
        }
    }
}
