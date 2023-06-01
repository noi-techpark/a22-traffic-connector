-- SPDX-FileCopyrightText: NOI Techpark <digital@noi.bz.it>
--
-- SPDX-License-Identifier: AGPL-3.0-or-later

/*
    A22 traffic API connector.

    Retrieve A22 traffic data and store it into a PostgreSQL database.

    (C) 2018 IDM Suedtirol - Alto Adige
    (C) 2019 NOI Techpark Südtirol / Alto Adige

    Author: Chris Mair - chris@1006.org  
 */
DROP SCHEMA IF EXISTS a22 CASCADE;
CREATE SCHEMA a22;

-- the list of sensors
-- code has the form 'A22:coil_id:sensor_id'
CREATE TABLE a22.a22_station (
    code text primary key,
    name text NOT NULL,
    geo text NOT NULL
);


-- the list of ghost sensors (unknown sensors that have sent data)
-- code has the form 'A22:coil_id:sensor_id'
CREATE TABLE a22.a22_ghost_station (
    code text primary key,
    inserted_when timestamptz NOT NULL default now()
);


-- the web service credentials, the application will look for credentials
-- stored at id = 1
CREATE TABLE a22.a22_webservice (
    id serial primary key,
    url character varying NOT NULL,
    username character varying NOT NULL,
    password character varying,
    unique(url)
);

-- the traffic transit events
-- note there is intentionally no foreign key linking stationcode to a22.a22_station 
CREATE TABLE a22.a22_traffic (
    stationcode text,
    "timestamp" integer,
    distance double precision,
    headway double precision,
    length double precision,
    axles integer,
    against_traffic boolean,
    class integer,
    speed double precision,
    direction integer
);

CREATE INDEX a22_traffic_stationcode_ix ON a22.a22_traffic USING btree (stationcode);
CREATE INDEX a22_traffic_timestamp_ix ON a22.a22_traffic USING btree ("timestamp");


-- a view on the list of sensors that adds a numeric code for the lane
CREATE VIEW a22.a22_station_v AS
 SELECT a22_station.code,
    a22_station.name,
    a22_station.geo,
        CASE
            WHEN a22_station.name ~ '\(corsia di marcia nord,'::text THEN 1
            WHEN a22_station.name ~ '\(corsia di sorpasso nord,'::text THEN 2
            WHEN a22_station.name ~ '\(corsia di marcia sud,'::text THEN 3
            WHEN a22_station.name ~ '\(corsia di sorpasso sud,'::text THEN 4
            WHEN a22_station.name ~ '\(corsia di emergenza nord,'::text THEN 5
            WHEN a22_station.name ~ '\(corsia di emergenza sud,'::text THEN 6
            ELSE NULL::integer
        END AS lane_code
   FROM a22.a22_station;

