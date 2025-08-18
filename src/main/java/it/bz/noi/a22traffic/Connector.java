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

import java.io.*;
import java.net.*;
import java.time.ZonedDateTime;
import java.util.*;
import org.json.simple.*;

/**
 * A22 traffic API connector: A22 web interface abstraction.
 */
public class Connector {

    private static final int WS_CONN_TIMEOUT_MSEC = 30000;
    private static final int WS_READ_TIMEOUT_MSEC = 1800000;
    private static final boolean DEBUG = true;

    private String token = null;
    private final String url;
    private final String auth_json; // Store auth details for re-authentication

    /**
     * Authenticates and stores the session token.
     *
     * @throws java.io.IOException
     */
    private void authenticate() throws IOException {
        // make authentication request
        HttpURLConnection conn = (HttpURLConnection) (new URL(url + "/token")).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "IDM/traffic_a22");
        conn.setRequestProperty("Accept", "*/*");
        conn.setConnectTimeout(WS_CONN_TIMEOUT_MSEC);
        conn.setReadTimeout(WS_READ_TIMEOUT_MSEC);
        conn.setDoOutput(true);
        OutputStreamWriter os = new OutputStreamWriter(conn.getOutputStream());
        os.write(this.auth_json + "\n");
        os.flush();
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new RuntimeException("authentication failure (response code was " + status + ")");
        }

        // get response
        BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
        StringBuilder response = new StringBuilder();
        String s;
        while ((s = br.readLine()) != null) {
            response.append(s);
        }
        os.close();
        conn.disconnect();

        // parse response and store authentication token
        String session_id = null;
        try {
            JSONObject response_json = (JSONObject) JSONValue.parse(response.toString());
            JSONObject subscribe_result = (JSONObject) response_json.get("SubscribeResult");
            session_id = (String) subscribe_result.get("sessionId");
        } catch (Exception e) {
            // null pointer or cast exception in case the json hasn't the expected form
            throw new RuntimeException("authentication failure (could not parse response)");
        }

        if (session_id == null) {
            throw new RuntimeException("authentication failure (could not find sessionId in response)");
        }

        this.token = session_id;
        System.out.println("auth OK, new token = " + this.token.replaceAll(".{12}$", "************") + ", time = " + ZonedDateTime.now());
    }


    /**
     * Get authentication token and store it.
     *
     * @param url - the A22 web service URL
     * @param auth_json - the authentication String with user/pass in JSON
     *
     * @throws java.io.IOException
     *
     */
    public Connector(String url, String auth_json) throws IOException {
        this.url = url;
        this.auth_json = auth_json;
        this.authenticate(); // Initial authentication
    }

    /**
     * Release authentication token.
     *
     * @throws IOException
     */
    public void close() throws IOException {

        if (url == null || token == null) {
            throw new RuntimeException("there is no authenticated session");
        }

        // make de-authentication request
        HttpURLConnection conn = (HttpURLConnection) (new URL(url + "/token/" + token)).openConnection();
        conn.setRequestMethod("DELETE");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "IDM/traffic_a22");
        conn.setRequestProperty("Accept", "*/*");
        conn.setConnectTimeout(WS_CONN_TIMEOUT_MSEC);
        conn.setReadTimeout(WS_READ_TIMEOUT_MSEC);
        conn.setDoOutput(true);
        OutputStreamWriter os = new OutputStreamWriter(conn.getOutputStream());
        os.write("\n");
        os.flush();
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new RuntimeException("de-authentication failure (response code was " + status + ")");
        }

        // get response
        BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
        StringBuilder response = new StringBuilder();
        String s;
        while ((s = br.readLine()) != null) {
            response.append(s);
        }
        os.close();
        conn.disconnect();

        // parse response
        Boolean result = null;
        try {
            JSONObject response_json = (JSONObject) JSONValue.parse(response.toString());
            result = (Boolean) response_json.get("RemoveSubscribeResult");
        } catch (Exception e) {
            // null pointer or cast exception in case the json hasn't the expected form
            throw new RuntimeException("de-authentication failure (could not parse response)");
        }
        if (result == null || result != true) {
            throw new RuntimeException("de-authentication failure (de-authentication was not confirmed)");
        }

        System.out.println("de-auth OK, old token = " + token.replaceAll(".{12}$", "************") + ", time = " + ZonedDateTime.now());

        token = null;

    }

    /**
     * Retrieve the list of countries
     *
     * @return a Map of country codes by ID
     *
     * @throws IOException
     */
    public Map<String, String> getCountries() throws IOException {
        if (url == null || token == null) {
            throw new RuntimeException("there is no authenticated session");
        }

        // make request
        HttpURLConnection conn = (HttpURLConnection) (new URL(url + "/traffico/nazionalita")).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "IDM/traffic_a22");
        conn.setRequestProperty("Accept", "*/*");
        conn.setConnectTimeout(WS_CONN_TIMEOUT_MSEC);
        conn.setReadTimeout(WS_READ_TIMEOUT_MSEC);
        conn.setDoOutput(true);
        OutputStreamWriter os = new OutputStreamWriter(conn.getOutputStream());
        os.write("{\"sessionId\":\"" + token + "\"}\n");
        os.flush();
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new RuntimeException("could not retrieve nationality list (response code was " + status + ")");
        }

        // get response
        BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
        StringBuilder response = new StringBuilder();
        String s;
        while ((s = br.readLine()) != null) {
            response.append(s);
        }
        os.close();
        conn.disconnect();

        // parse response
        HashMap<String, String> output = new HashMap<>();
        try {
            JSONObject response_json = (JSONObject) JSONValue.parse(response.toString());
            JSONArray nationalities = (JSONArray) response_json.get("Traffico_GetNazionalitaResult");
            for (Object o : nationalities) {
                JSONObject n = (JSONObject) o;
                String id = "" + n.get("Id");
                String code = "" + n.get("Sigla");
                output.put(id, code);
            }
        } catch (Exception e) {
            // null pointer or cast exception in case the json hasn't the expected form
            throw new RuntimeException("could not parse nationality list");
        }

        if (DEBUG) {
            System.out.println("getTrafficSensors - got list of " + output.size() + " sensors");
        }

        return output;
    }

    /**
     * Retrieve the list of traffic sensors.
     *
     * @return an ArrayList of HashMaps with the sensor info
     *
     * @throws IOException
     */
    public ArrayList<HashMap<String, String>> getTrafficSensors() throws IOException {

        if (url == null || token == null) {
            throw new RuntimeException("there is no authenticated session");
        }

        // make request
        HttpURLConnection conn = (HttpURLConnection) (new URL(url + "/traffico/anagrafica")).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "IDM/traffic_a22");
        conn.setRequestProperty("Accept", "*/*");
        conn.setConnectTimeout(WS_CONN_TIMEOUT_MSEC);
        conn.setReadTimeout(WS_READ_TIMEOUT_MSEC);
        conn.setDoOutput(true);
        OutputStreamWriter os = new OutputStreamWriter(conn.getOutputStream());
        os.write("{\"sessionId\":\"" + token + "\"}\n");
        os.flush();
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new RuntimeException("could not retrieve traffic sensor list (response code was " + status + ")");
        }

        // get response
        BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
        StringBuilder response = new StringBuilder();
        String s;
        while ((s = br.readLine()) != null) {
            response.append(s);
        }
        os.close();
        conn.disconnect();

        // parse response
        ArrayList<HashMap<String, String>> output = new ArrayList<>();
        try {
            JSONObject response_json = (JSONObject) JSONValue.parse(response.toString());
            JSONArray coil_list = (JSONArray) response_json.get("Traffico_GetAnagraficaResult");
            int i, j;
            for (i = 0; i < coil_list.size(); i++) {
                JSONObject coil = (JSONObject) coil_list.get(i);
                JSONArray sensor_list = (JSONArray) coil.get("sensori");
                // note sensor_list can be empty (no sensors available)
                for (j = 0; j < sensor_list.size(); j++) {

                    JSONObject sensor = (JSONObject) sensor_list.get(j);
                    HashMap<String, String> h = new HashMap<>();
                    h.put("stationcode", "A22:" + coil.get("idspira") + ":" + sensor.get("idsensore"));
                    h.put("name", coil.get("descrizione") + " (" + getLaneText((Long) sensor.get("idcorsia") + "", (Long) sensor.get("iddirezione") + "") + ")");
                    h.put("pointprojection", "" + coil.get("latitudine") + "," + coil.get("longitudine"));

                    // we also want the raw metadata:
                    // for each sensor, we add all the common fields (besides "sensori") and the specific fields for this sensor
                    HashMap<String, String> raw_metadata = new HashMap<>();
                    for (Object key : coil.keySet()) {
                        if ("sensori".equals((String)key)) {
                            continue;
                        }
                        raw_metadata.put((String)key, String.valueOf(coil.get((String)key)));
                    }
                    for (Object key : sensor.keySet()) {
                        raw_metadata.put((String)key, String.valueOf(sensor.get((String)key)));
                    }
                    h.put("raw_metadata", hashToJSONText(raw_metadata));

                    output.add(h);

                }
            }
        } catch (Exception e) {
            // null pointer or cast exception in case the json hasn't the expected form
            throw new RuntimeException("could not parse traffic sensor list");
        }

        if (DEBUG) {
            System.out.println("getTrafficSensors - got list of " + output.size() + " sensors");
        }

        return output;
    }
    /**
     * Retrieve the list of vehicle transit events for all known sensors.
     *
     * @param thread_num thread number, just for debug prints
     *
     * @param fr search events from this timestamp (Unix epoch in UTC)
     *
     * @param to search events up to *and* *including* this timestamp (Unix epoch in UTC)
     *
     * @param sensors list of sensors as returned by getTrafficSensors() or null (to get them all)
     *
     * @param coils_fr if this is not null, then it overrides parameter fr (this is *per* coil_id)
     *
     *
     * @return an ArrayList of HashMaps with the vehicle transit event info
     *
     * @throws IOException
     */
    public ArrayList<HashMap<String, String>> getVehicles(int thread_num, long fr, long to, ArrayList<HashMap<String, String>> sensors, HashMap<String, Integer> coils_fr) throws IOException {

        if (url == null || token == null) {
            throw new RuntimeException("there is no authenticated session");
        }

        ArrayList<HashMap<String, String>> output = new ArrayList<>();

        HashMap<Integer, Integer> http_codes = new HashMap<>();

        // convert to format used by A22
        // (see the comment "Reverse engineering the A22 timestamp format" at the end of the file)
        String frTS = fr + "000+0000";
        String toTS = to + "999+0000";

        // retrieve the list of sensors (unless we got them already)
        if (sensors == null) {
            sensors = getTrafficSensors();
        }

        // extract the unique coil IDs
        HashMap<String, Integer> coils = new HashMap<>();
        int sid;
        for (sid = 0; sid < sensors.size(); sid++) {
            // stationcode = A22:coilid:sensorid
            String split[] = sensors.get(sid).get("stationcode").split(":");
            if (split.length != 3) {
                throw new RuntimeException("stationcode does not have the expected format");
            }
            coils.put(split[1], 1);
        }
        if (coils.isEmpty()) {
            return output;
        }

        // loop over the coil IDs and retrieve transit events for each coil ID, adding to the output list
        for (String coilid : coils.keySet()) {
            if (coils_fr != null) {
                if (coils_fr.get(coilid) == null) {
                    throw new RuntimeException("got per coil from timestamps, but cannot find coil id '" + coilid + "'");
                }
                frTS = coils_fr.get(coilid) + "000+0000";
            }
            
            final int MAX_RETRIES = 10;
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                if (DEBUG) {
                    System.out.println("getVehicles is retrieving vehicle transit events for coil ID " + coilid + " interval " + frTS + " - " + toTS + ". Attempt " + attempt + "/" + MAX_RETRIES);
                }

                HttpURLConnection conn = null;
                try {
                    // make request
                    conn = (HttpURLConnection) (new URL(url + "/traffico/transiti")).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("User-Agent", "IDM/traffic_a22");
                    conn.setRequestProperty("Accept", "*/*");
                    conn.setConnectTimeout(WS_CONN_TIMEOUT_MSEC);
                    conn.setReadTimeout(WS_READ_TIMEOUT_MSEC);
                    conn.setDoOutput(true);
                    OutputStreamWriter os = new OutputStreamWriter(conn.getOutputStream());
                    os.write("{\"request\":{\"sessionId\":\"" + token + "\",\"idspira\":" + coilid + ",\"fromData\":\"/Date(" + frTS + ")/\",\"toData\":\"/Date(" + toTS + ")/\"}}\n");
                    os.flush();

                    int status = conn.getResponseCode();
                    http_codes.put(status, http_codes.getOrDefault(status, 0) + 1);

                    if (status == 401) {
                        // --- AUTHENTICATION ERROR ---
                        System.err.println("WARN: Received 401 Unauthorized for coil ID " + coilid + ". Attempt " + attempt + "/" + MAX_RETRIES + ". Re-authenticating...");
                        if (attempt == MAX_RETRIES) {
                             System.err.println("ERROR: Authentication failed after " + MAX_RETRIES + " attempts. Skipping coil " + coilid + ".");
                             break; // Give up
                        }
                        this.authenticate(); // Get a new token

                        try {
                            Thread.sleep(25L * attempt); // Sleep with increasing delay
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        // Continue to next attempt
                        continue;
                    } else if (status != 200) {
                        // --- OTHER ERRORS ---
                        if (DEBUG) {
                            System.out.println("    +- skipping (response status was " + status + ")");
                        }
                        // For other errors (e.g., 500), break the retry loop and skip this coil
                        break;
                    }
                
                    // --- SUCCESS ---
                    JSONObject response_json = (JSONObject) JSONValue.parse(new InputStreamReader(conn.getInputStream()));
                    os.close();

                    JSONArray event_list = (JSONArray) response_json.get("Traffico_GetTransitiResult");
                    if (DEBUG) {
                        System.out.println("    +- got " + event_list.size() + " events");
                    }
                    for (Object event_obj : event_list) {
                        JSONObject event = (JSONObject) event_obj;
                        HashMap<String, String> h = new HashMap<>();
                        h.put("stationcode", "A22:" + event.get("idspira") + ":" + event.get("idsensore"));
                        h.put("distance", "" + event.get("distanza"));
                        h.put("headway", "" + event.get("avanzamento"));
                        h.put("speed", "" + event.get("velocita"));
                        h.put("length", "" + event.get("lunghezza"));
                        h.put("axles", "" + event.get("assi"));
                        h.put("class", "" + event.get("classe"));
                        h.put("direction", "" + event.get("direzione"));
                        h.put("country", "" + event.get("idNazionalita"));
                        h.put("license_plate_initials", "" + event.get("targaIniziali"));
                        h.put("timestamp", ("" + event.get("data")).substring(6, 16));
                        h.put("against_traffic", "" + (Boolean) event.get("controsenso"));
                        output.add(h);
                    }
                    
                } catch (Exception e) {
                    // null pointer or cast exception in case the json hasn't the expected form
                    e.printStackTrace();
                    throw new RuntimeException("could not parse vehicle transit events");
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }

                try {
                    Thread.sleep(25); // sleep a bit to avoid overloading the server
                    break; // Success, exit retry loop
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        } // for coilid

        if (DEBUG) {
            System.out.println("getVehicles summary - coils: " + coils.keySet().size() + ", sensors: " + sensors.size() + ", transit events: " + output.size());
            System.out.println("getVehicles summary - response codes: " + http_codes);
        }

        // keep track of response codes, even when not in debug mode for the time being
        System.out.println("th" + thread_num + " response codes: " + http_codes);

        return output;

    }

    private String getLaneText(String lane, String orientation) {

        String s = "corsia di ";

        // This is tricker than it looks:
        // Older sensor types use all 6 cases, depending on lane direction e.g. 5 is emergency lane direction nord, 6 is direction south
        // but newer CCTV camera types only use 1 and 2, irregardless of direction
        switch (lane) {
            case "1":
                s += "marcia";
                break;
            case "2":
                s += "sorpasso";
                break;
            case "3":
                s += "marcia";
                break;
            case "4":
                s += "sorpasso";
                break;
            case "5":
                s += "emergenza";
                break;
            case "6":
                s += "emergenza";
                break;
            default:
                s += "n/a";
        }

        s += ", direzione ";

        switch (orientation) {
            case "1":
                s += "sud";
                break;
            case "2":
                s += "nord";
                break;
            case "3":
                s += "entrambe";
                break;
            case "4":
                s += "non definita";
                break;
            default:
                s += "n/a";
        }

        return s;

    }

    private static String quoteString(String input) {
        return "\"" + input.replaceAll("\"", "\\\\\"") + "\"";
    }
    private static String hashToJSONText(HashMap<String,String> map) {
        // sort by key for stable JSON representation
        StringBuffer ret = new StringBuffer();
        map.keySet().stream().sorted().forEach(key -> {
            if (ret.length() != 0) {
                ret.append(",");
            }
            ret.append(quoteString(key)).append(":").append(quoteString(map.get(key)));
        });
        return "{" + ret.toString() + "}";
    }


    /*

    Reverse engineering the A22 timestamp format
    --------------------------------------------

    In CET we have daylight saving change from +2 to +1
    on 28 oct 2018 (the clock goes from 3:00 to 2:00).

    These are events from one sensor around the daylight change:

    [...]
    /Date(1540688331000+0200)/
    /Date(1540688331000+0200)/
    /Date(1540688358000+0200)/
    /Date(1540688358000+0200)/
    /Date(1540688390000+0200)/
    /Date(1540688560000+0100)/
    /Date(1540688645000+0100)/
    /Date(1540688645000+0100)/
    /Date(1540688648000+0100)/
    /Date(1540688656000+0100)/
    [...]

    /Date(1540688390000+0200)/

        $ date --date='@1540688390'
        Sun Oct 28 00:59:50 UTC 2018

        00:59 UTC would be 02:59 CET ~ 03 CET


    /Date(1540688560000+0100)/

        $ date --date='@1540688560'
        Sun Oct 28 01:02:40 UTC 2018

        01:02 UTC would be 02:02 CET ~ 02 CET

    That means the first part of this string *is* the correct timestamp
    in unix epoch / UTC.

     */
}